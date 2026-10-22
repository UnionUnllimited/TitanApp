import CryptoKit
import Foundation
import NetworkExtension

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
    private var connectsInFlight = 0
    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?
    /// The profile's first load: an intent may come right at launch.
    private var loading: Task<Void, Never>?

    /// The app's own, while it runs: the Live Activity's ‹ › switch through it (VPNIntents).
    static weak var current: VPNController?

    /// Настройки → Не отключать VPN (on unless turned off there): VPN On Demand, so iOS itself
    /// brings the VPN back after a network change, a failure or a restart of the phone, until
    /// it is turned off by hand.
    nonisolated static let keepAliveKey = "keepAlive"
    nonisolated static var keepAlive: Bool { UserDefaults.standard.object(forKey: keepAliveKey) as? Bool ?? true }

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
        case .connected: set(.connected(since: connection.connectedDate ?? Date()))
        case .connecting, .reasserting: set(.connecting)
        case .disconnecting: set(.disconnecting)
        default:
            if case .error = state {} else { set(.off) }
            // It fell while starting: show the tunnel's reason instead of a plain "Не подключено".
            if wasConnecting {
                connection.fetchLastDisconnectError { [weak self] error in
                    guard let error else { return }
                    Task { @MainActor in
                        guard let self, case .off = self.state else { return }
                        self.state = .error(Self.describe(error))
                    }
                }
            }
        }
    }

    /// Status notifications come for any VPN and again for the same status (a ping's pause and
    /// its resume send many): the same state again doesn't redraw every screen.
    private func set(_ new: State) {
        if state != new { state = new }
    }

    func connect(_ server: Server) async {
        wantOn = true
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
            // a network change) until it is turned off by hand.
            let rule = NEOnDemandRuleConnect()
            rule.interfaceTypeMatch = .any
            m.onDemandRules = [rule]
            m.isOnDemandEnabled = Self.keepAlive
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

    /// What a profile's config was built from: the subscription's server as it was then.
    nonisolated static func source(_ server: Server) -> String {
        SHA256.hash(data: Data((server.xrayJson ?? "").utf8)).map { String(format: "%02x", $0) }.joined()
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
        try? await m.saveToPreferences()
    }

    /// Не отключать VPN switched in the settings: applies to the VPN profile at once. Off even
    /// while the VPN is down (iOS may be about to bring a fallen one back); on only while it is
    /// on: an off by hand stays off, and a ping's pause or a connect under way gets it from connect.
    func setKeepAlive(_ on: Bool) {
        guard let m = manager, !on || (isOn && connectsInFlight == 0) else { return }
        Task {
            try? await m.loadFromPreferences()
            // Turned off meanwhile: stays off.
            guard m.isOnDemandEnabled != on, !on || isOn else { return }
            m.isOnDemandEnabled = on
            try? await m.saveToPreferences()
        }
    }

    /// Turns the tunnel off for a ping: through it nearly every probe times out (the app's own
    /// traffic goes into the VPN). Не отключать VPN is held off so iOS doesn't bring it back
    /// meanwhile; `connect` restores it. Returns once the tunnel is down; false if it was off.
    func pauseForPing() async -> Bool {
        guard isOn, let m = manager else { return false }
        wantOn = false
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
    func tunnelTFOStats() async -> [String: Any]? {
        guard isOn, let session = manager?.connection as? NETunnelProviderSession else { return nil }
        return await withCheckedContinuation { cont in
            do {
                try session.sendProviderMessage(Data("tfoStats".utf8)) { data in
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

    /// Turned off outside the app's screen (Пункт управления, Siri): a connect or a server
    /// switch under way gives up instead of bringing the VPN back.
    func cancelConnects() {
        wantOn = false
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
