import CryptoKit
import Foundation
import NetworkExtension
import UIKit

/// Turns the VPN on and off through our packet tunnel extension (TitanTunnel.appex).
/// The config to run is handed over when the tunnel starts (and kept in the VPN profile for
/// starts from iOS Settings), so the app and the extension need no shared App Group: a
/// re-signing service can't break that part.
@MainActor
final class VPNController: ObservableObject {
    enum State: Equatable { case off, connecting, connected(since: Date), disconnecting, error(String) }

    @Published private(set) var state: State = .off
    /// Connect attempts that failed (the Выкл | Вкл knob goes back with a shake).
    @Published private(set) var failures = 0
    /// What was asked for last: Выкл while connect is still saving the profile wins.
    private var wantOn = false
    /// Connects (and server switches) under way: the profile is theirs to save until they end.
    /// They run on in the background: the app left mid-switch (the VPN down, on demand held off)
    /// would else be suspended with the VPN off until it is opened again.
    private var connectsInFlight = 0 {
        didSet { if connectsInFlight > 0 { connectTime.begin() } else { connectTime.end() } }
    }
    private let connectTime = BackgroundTime("VPN connect")
    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?
    /// The profile's first load: an intent may come right at launch.
    private var loading: Task<Void, Never>?
    /// Bumped by each connect and each stop of ours: a failed start acts only if none came since.
    private var attempts = 0
    /// Our own start until the tunnel is up (seen once it says connecting: a late status of the
    /// session before doesn't count). Falling before that is a failed start, not a drop. Every
    /// stop of ours clears it.
    private var starting: Start?
    private struct Start {
        let attempt: Int
        var seen: Bool
    }
    /// Bumped by each off by hand (Выкл, Выйти, Пункт управления, Siri, ⏻): a ping's resume
    /// that would bring the VPN back after one doesn't run (AppStore.endPing).
    private(set) var offsByHand = 0

    /// The app's own, while it runs: the Live Activity's ‹ › switch through it (VPNIntents).
    static weak var current: VPNController?

    /// Настройки → Не отключать VPN (on unless turned off there): VPN On Demand, so iOS itself
    /// brings the VPN back after a network change, a failure or a restart of the phone, until
    /// it is turned off by hand.
    nonisolated static let keepAliveKey = "keepAlive"
    nonisolated static var keepAlive: Bool { UserDefaults.standard.object(forKey: keepAliveKey) as? Bool ?? true }
    /// Технический режим lets bypass servers run on Wi-Fi (and on demand start them there).
    private static var bypassOnWifi: Bool { UserDefaults.standard.bool(forKey: TechMode.key) }

    init() {
        loading = Task { await load() }
        Self.current = self
    }

    /// Returns once the profile has been read (state is the VPN's real one from then on).
    func loaded() async {
        await loading?.value
    }

    var isOn: Bool {
        switch state { case .connecting, .connected: return true; default: return false }
    }

    /// A connect or a server switch is under way (the tunnel may be down for a moment in it).
    var connectsUnderWay: Bool { connectsInFlight > 0 }

    var isConnected: Bool {
        if case .connected = state { return true }
        return false
    }

    /// The extension's bundle id as it is in this build (a re-signing service may change it).
    /// The packet tunnel's: TitanWidgets.appex is next to it.
    private static var tunnelBundleId: String? {
        guard let plugins = Bundle.main.builtInPlugInsURL,
              let items = try? FileManager.default.contentsOfDirectory(at: plugins, includingPropertiesForKeys: nil) else { return nil }
        let tunnel = items.filter { $0.pathExtension == "appex" }.compactMap { Bundle(url: $0) }.first { bundle in
            let ext = bundle.infoDictionary?["NSExtension"] as? [String: Any]
            return ext?["NSExtensionPointIdentifier"] as? String == "com.apple.networkextension.packet-tunnel"
        }
        return tunnel?.bundleIdentifier
    }

    private func load() async {
        let managers = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        manager = managers.first
        observe()
        update()
    }

    private func observe() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.update() }
        }
    }

    private func update() {
        guard let connection = manager?.connection else { set(.off); return }
        let wasConnecting = state == .connecting
        switch connection.status {
        case .connected:
            starting = nil
            set(.connected(since: connection.connectedDate ?? Date()))
        case .connecting, .reasserting:
            starting?.seen = true
            set(.connecting)
        case .disconnecting: set(.disconnecting)
        default:
            if case .error = state {} else { set(.off) }
            // Our own start fell before it was ever up (a config the tunnel can't run, say).
            let failed = starting?.seen == true ? starting?.attempt : nil
            starting = nil
            // It fell while starting: show the tunnel's reason instead of a plain "Не подключено".
            if wasConnecting || failed != nil {
                connection.fetchLastDisconnectError { [weak self] error in
                    guard let error else { return }
                    Task { @MainActor in
                        guard let self else { return }
                        if let failed { self.startFailed(failed) }
                        guard case .off = self.state else { return }
                        self.state = .error(Self.describe(error))
                    }
                }
            }
        }
    }

    /// A start of ours fell before the tunnel was up: Не отключать VPN would start the same
    /// config again and again (Подключаемся… / Не подключено with no end, which Выкл can't stop
    /// from Не подключено). On demand goes off with it; a fall once it was up keeps it (iOS
    /// brings the VPN back). Not once a newer connect has begun.
    private func startFailed(_ attempt: Int) {
        guard attempt == attempts, let m = manager else { return }
        wantOn = false
        Task {
            try? await m.loadFromPreferences()
            guard attempt == attempts, m.isOnDemandEnabled else { return }
            m.isOnDemandEnabled = false
            try? await m.saveToPreferences()
        }
    }

    /// A stop of ours or a newer connect: the start under way is no longer watched.
    private func forgetStart() {
        attempts += 1
        starting = nil
    }

    /// Status notifications come for any VPN and again for the same status (a ping's pause and
    /// its resume send many): the same state again doesn't redraw every screen.
    private func set(_ new: State) {
        if state != new { state = new }
    }

    func connect(_ server: Server) async {
        wantOn = true
        // An older connect's start still under way is this one's to watch now.
        forgetStart()
        let attempt = attempts
        connectsInFlight += 1
        defer { connectsInFlight -= 1 }
        guard let tunnelId = Self.tunnelBundleId else {
            fail("В этой сборке нет VPN-расширения")
            return
        }
        // Подключаемся… at once: the build below may take seconds (a plugin server's DNS
        // lookup), and Вкл again or Выкл meanwhile must find it on.
        state = .connecting
        do {
            // MASQUE rewritten for Xray's own client; TUIC / AnyTLS / ShadowTLS / SSH / Mieru
            // pointed at their client, which the tunnel starts first; Naive says it can't.
            // Off the main actor: the config's parsing (and a plugin server's DNS lookup) froze
            // the screen as a ping ended.
            let (prepared, config) = try await Task.detached(priority: .userInitiated) { () throws -> (PreparedServer, String) in
                let p = try Plugins.prepare(server, forTunnel: true)
                return (p, try XrayRunConfig.build(serverJson: p.json))
            }.value
            let m = manager ?? NETunnelProviderManager()
            // Пункт управления, Siri or iOS Settings may have changed the profile since it was
            // loaded: saving over the old copy would fail.
            if manager != nil { try? await m.loadFromPreferences() }
            let proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
            proto.providerBundleIdentifier = tunnelId
            proto.serverAddress = server.name
            var provider: [String: Any] = ["config": config, "source": Self.source(server)]
            if let plugin = prepared.plugin { provider["plugin"] = plugin }
            proto.providerConfiguration = provider
            proto.disconnectOnSleep = false
            m.protocolConfiguration = proto
            m.localizedDescription = "Titan VPS"
            m.isEnabled = true
            // Не отключать VPN: iOS itself brings the VPN back (after a failure, a restart, sleep,
            // a network change) until it is turned off by hand. Not if Выкл or a ping's pause came
            // meanwhile: iOS would start it on the save.
            m.onDemandRules = TunnelSwitch.onDemandRules(server.name, bypassOnWifi: Self.bypassOnWifi)
            m.isOnDemandEnabled = wantOn && Self.keepAlive
            // Still wanted: Подключаемся… again if a late status of the tunnel stopped before (a
            // switch) put it off meanwhile. Выкл or a ping's pause meanwhile keep theirs.
            if wantOn { state = .connecting }
            try await m.saveToPreferences()
            // Saving the first time asks the user to allow the VPN; reload to get a usable manager.
            try await m.loadFromPreferences()
            manager = m
            observe()
            // Turned off (or paused for a ping) while the config was built or the profile saved:
            // stay off.
            guard wantOn else {
                if m.isOnDemandEnabled {
                    m.isOnDemandEnabled = false
                    try? await m.saveToPreferences()
                }
                // Не отключать VPN may have started it on the save already.
                m.connection.stopVPNTunnel()
                state = .off
                update()
                return
            }
            var options: [String: NSObject] = ["config": config as NSString]
            if let plugin = prepared.plugin { options["plugin"] = plugin as NSString }
            try m.connection.startVPNTunnel(options: options)
            // Watched until it is up: falling before that is a failed start (startFailed). Already
            // connecting: on demand started it on the save. Already up (on demand had it up
            // before the reload ended): nothing to watch, a later fall is a drop that on demand
            // brings back; the state as the status is (on a first connect it came with no
            // manager yet and read as off).
            let status = m.connection.status
            if status == .connected || status == .reasserting {
                update()
            } else if attempt == attempts {
                starting = Start(attempt: attempt, seen: status == .connecting)
            }
            // Не отключать VPN switched while the profile was saving (setKeepAlive leaves that to
            // connect): as switched now.
            while wantOn, m.isOnDemandEnabled != Self.keepAlive {
                m.isOnDemandEnabled = Self.keepAlive
                try? await m.saveToPreferences()
            }
        } catch {
            fail(Self.describe(error))
        }
    }

    private func fail(_ message: String) {
        state = .error(message)
        failures += 1
    }

    func disconnect() {
        wantOn = false
        offsByHand += 1
        forgetStart()
        state = .disconnecting
        guard let m = manager else { return }
        Task {
            // Turned off by hand: iOS must not bring it back on its own.
            await holdOnDemand(m)
            m.connection.stopVPNTunnel()
        }
    }

    /// On demand off in the profile as saved now (see connect), before the tunnel is stopped:
    /// else iOS starts it again at once.
    private func holdOnDemand(_ m: NETunnelProviderManager) async {
        try? await m.loadFromPreferences()
        guard m.isOnDemandEnabled else { return }
        m.isOnDemandEnabled = false
        try? await m.saveToPreferences()
    }

    /// What a profile's config was built from: the subscription's server as it was then, and the
    /// XrayRunConfig that built it.
    nonisolated static func source(_ server: Server) -> String {
        SHA256.hash(data: Data((server.xrayJson ?? "").utf8)).map { String(format: "%02x", $0) }.joined()
            + "-v\(XrayRunConfig.version)"
    }

    /// The profile's config was built by an older XrayRunConfig (or before `source` existed).
    private static func builtByOlder(_ proto: NETunnelProviderProtocol) -> Bool {
        !((proto.providerConfiguration?["source"] as? String)?.hasSuffix("-v\(XrayRunConfig.version)") ?? false)
    }

    /// The subscription refreshed while off: iOS Settings and Не отключать VPN start the profile
    /// with no options, so its config follows the subscription (the picked server if its own is
    /// gone). Only an existing profile, never while on or while a connect is under way.
    func refreshProfile(_ servers: [Server], picked: Server?) async {
        guard let m = manager, connectsInFlight == 0, !isOn, state != .disconnecting else { return }
        try? await m.loadFromPreferences()
        let status = m.connection.status
        guard connectsInFlight == 0, status == .disconnected || status == .invalid,
              let proto = m.protocolConfiguration as? NETunnelProviderProtocol else { return }
        let own = servers.first { $0.name == proto.serverAddress }
        // Its server as the subscription has it now: nothing to save.
        if let own, (proto.providerConfiguration?["source"] as? String) == Self.source(own) { return }
        guard let server = own ?? picked,
              let prepared = try? Plugins.prepare(server, forTunnel: true),
              let config = try? XrayRunConfig.build(serverJson: prepared.json) else { return }
        var provider: [String: Any] = ["config": config, "source": Self.source(server)]
        if let plugin = prepared.plugin { provider["plugin"] = plugin }
        proto.serverAddress = server.name
        proto.providerConfiguration = provider
        m.protocolConfiguration = proto
        m.onDemandRules = TunnelSwitch.onDemandRules(server.name, bypassOnWifi: Self.bypassOnWifi)
        try? await m.saveToPreferences()
    }

    /// A server's providerConfiguration as connect builds it, off the main actor (a plugin
    /// server's DNS lookup); nil if it can't be built.
    private static func provider(_ server: Server) async -> [String: Any]? {
        let build = Task.detached(priority: .utility) { () throws -> (PreparedServer, String) in
            let p = try Plugins.prepare(server, forTunnel: true)
            return (p, try XrayRunConfig.build(serverJson: p.json))
        }
        guard let built = try? await build.value else { return nil }
        var provider: [String: Any] = ["config": built.1, "source": source(server)]
        if let plugin = built.0.plugin { provider["plugin"] = plugin }
        return provider
    }

    /// Технический режим switched: a bypass server's on demand follows it (TunnelSwitch.onDemandRules),
    /// not only from the next connect. Saved with the VPN on or off; a connect under way sets its own.
    func applyOnDemandRules() async {
        guard let m = manager, connectsInFlight == 0 else { return }
        try? await m.loadFromPreferences()
        guard connectsInFlight == 0, let name = (m.protocolConfiguration as? NETunnelProviderProtocol)?.serverAddress,
              TunnelSwitch.isBypass(name) else { return }
        m.onDemandRules = TunnelSwitch.onDemandRules(name, bypassOnWifi: Self.bypassOnWifi)
        try? await m.saveToPreferences()
    }

    /// Не отключать VPN switched in the settings (or the profile found not as set there once
    /// connected): applies to the VPN profile at once. Off even while the VPN is down (iOS may be
    /// about to bring a fallen one back); on only while it is on: an off by hand stays off, and a
    /// ping's pause or a connect under way gets it from connect.
    /// Connected (RootView, with the subscription's servers): a profile an older XrayRunConfig
    /// built gets today's config too. On demand kept the VPN on across the app's update, and every
    /// start by iOS (after a kill as well) ran the old one until a connect from the app.
    func setKeepAlive(_ on: Bool, rebuilding servers: [Server] = []) {
        guard let m = manager, !on || (isOn && connectsInFlight == 0) else { return }
        Task {
            try? await m.loadFromPreferences()
            var provider: [String: Any]?
            if on, let proto = m.protocolConfiguration as? NETunnelProviderProtocol, Self.builtByOlder(proto),
               let server = servers.first(where: { $0.name == proto.serverAddress }),
               let built = await Self.provider(server) {
                // The profile as it is now: Siri or iOS Settings may have saved it meanwhile, and a
                // connect under way saves its own.
                try? await m.loadFromPreferences()
                if connectsInFlight == 0, let now = m.protocolConfiguration as? NETunnelProviderProtocol,
                   now.serverAddress == server.name, Self.builtByOlder(now) { provider = built }
            }
            // Turned off meanwhile: stays off.
            guard m.isOnDemandEnabled != on || provider != nil, !on || isOn else { return }
            m.isOnDemandEnabled = on
            if on, let proto = m.protocolConfiguration as? NETunnelProviderProtocol {
                // A profile saved before this switch existed: the rules for its server too.
                if let name = proto.serverAddress {
                    m.onDemandRules = TunnelSwitch.onDemandRules(name, bypassOnWifi: Self.bypassOnWifi)
                }
                // For the next start only: the tunnel up now keeps the config it runs. Were iOS to
                // restart it on the save, on demand brings it back on the new one.
                if let provider {
                    proto.providerConfiguration = provider
                    m.protocolConfiguration = proto
                }
            }
            try? await m.saveToPreferences()
        }
    }

    /// Turns the tunnel off for a ping: through it nearly every probe times out (the app's own
    /// traffic goes into the VPN). Не отключать VPN is held off so iOS doesn't bring it back
    /// meanwhile; `connect` restores it. Returns once the tunnel is down; false if it was off.
    func pauseForPing() async -> Bool {
        guard isOn, let m = manager else { return false }
        wantOn = false
        forgetStart()
        await holdOnDemand(m)
        m.connection.stopVPNTunnel()
        for _ in 0..<50 {
            let status = m.connection.status
            if status == .disconnected || status == .invalid { break }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        // Routes and DNS of the network itself come back a moment later.
        try? await Task.sleep(nanoseconds: 700_000_000)
        // Paused while a connect still built its config: no tunnel ran, so no status comes to say
        // off (that connect's `guard wantOn` stops it). Off now, so the ping's end brings it back.
        if !wantOn, state == .connecting { state = .off }
        return true
    }

    /// What the tunnel's TCP Fast Open dials did (its answer to "tfoStats"); nil while off.
    func tunnelTFOStats() async -> [String: Any]? { await askTunnel("tfoStats") }

    /// The tunnel's memory, restarts and kills (its answer to "mem"); nil while off.
    func tunnelMem() async -> [String: Any]? { await askTunnel("mem") }

    private func askTunnel(_ message: String) async -> [String: Any]? {
        guard isOn, let session = manager?.connection as? NETunnelProviderSession else { return nil }
        return await withCheckedContinuation { cont in
            do {
                try session.sendProviderMessage(Data(message.utf8)) { data in
                    cont.resume(returning: data.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] })
                }
            } catch {
                cont.resume(returning: nil)
            }
        }
    }

    /// Reconnects on another server while on.
    func switchTo(_ server: Server) async {
        guard isOn else { return }
        // Off for a moment in between: no refreshProfile then either.
        connectsInFlight += 1
        defer { connectsInFlight -= 1 }
        wantOn = true
        // The old session's end is no failed start.
        forgetStart()
        // Не отключать VPN off first: iOS would bring the old server back at once. connect turns
        // it on again.
        if let m = manager { await holdOnDemand(m) }
        manager?.connection.stopVPNTunnel()
        try? await Task.sleep(nanoseconds: 700_000_000)
        // Turned off meanwhile (Выкл, Пункт управления, Siri): stays off, as the tunnel is now.
        guard wantOn else {
            update()
            return
        }
        await connect(server)
    }

    /// Turned off outside the app's screen (Пункт управления, Siri, ⏻), or Выйти while off: a
    /// connect, a server switch or a ping's resume under way gives up instead of bringing the
    /// VPN back.
    func cancelConnects() {
        wantOn = false
        offsByHand += 1
        forgetStart()
    }

    /// Выйти and Удалить аккаунт: the VPN off for good and its profile gone from iOS Settings
    /// (it holds the signed-out key's config, which iOS Settings or on demand could still start).
    /// A connect under way gives up first (it saves the profile once more on its way out); a
    /// connect begun after this one (a new key) keeps its profile. iOS asks to allow the VPN
    /// again at the next connect.
    func removeProfile() {
        let wasOn = isOn
        cancelConnects()
        let attempt = attempts
        if wasOn { state = .disconnecting }
        Task {
            await loading?.value
            for _ in 0..<100 where connectsInFlight > 0 {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            guard attempt == attempts, let m = manager else { return }
            await holdOnDemand(m)
            m.connection.stopVPNTunnel()
            guard attempt == attempts else { return }
            if (try? await m.removeFromPreferences()) != nil, attempt == attempts { manager = nil }
            update()
        }
    }

    private static func describe(_ error: Error) -> String {
        let ns = error as NSError
        if ns.domain == NEVPNErrorDomain, ns.code == NEVPNError.configurationReadWriteFailed.rawValue {
            return "Разрешите добавление VPN-конфигурации, чтобы подключиться"
        }
        if let e = error as? SubscriptionError { return e.message }
        return "Не удалось подключиться: \(error.localizedDescription)"
    }
}

/// Background time for work that brings the VPN back (a connect, a ping's pause until its
/// resume): iOS suspends a left app soon, and such work cut short leaves the VPN off with on
/// demand held off until the app is opened again. Asked for when the work starts (free in the
/// foreground), given back when it ends or when iOS says the time is up.
@MainActor
final class BackgroundTime {
    private let name: String
    private var task: UIBackgroundTaskIdentifier = .invalid

    init(_ name: String) {
        self.name = name
    }

    func begin() {
        guard task == .invalid else { return }
        task = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in self?.end() }
    }

    func end() {
        guard task != .invalid else { return }
        UIApplication.shared.endBackgroundTask(task)
        task = .invalid
    }
}
