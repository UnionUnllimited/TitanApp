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

    init() {
        Task { await load() }
    }

    var isOn: Bool {
        switch state { case .connecting, .connected: return true; default: return false }
    }

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
        guard let connection = manager?.connection else { state = .off; return }
        let wasConnecting = state == .connecting
        switch connection.status {
        case .connected: state = .connected(since: connection.connectedDate ?? Date())
        case .connecting, .reasserting: state = .connecting
        case .disconnecting: state = .disconnecting
        default:
            if case .error = state {} else { state = .off }
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

    func connect(_ server: Server) async {
        wantOn = true
        connectsInFlight += 1
        defer { connectsInFlight -= 1 }
        guard let tunnelId = Self.tunnelBundleId else {
            fail("В этой сборке нет VPN-расширения")
            return
        }
        do {
            // MASQUE rewritten for Xray's own client; TUIC / AnyTLS / ShadowTLS / SSH / Mieru
            // pointed at their client, which the tunnel starts first; Naive says it can't.
            let prepared = try Plugins.prepare(server, forTunnel: true)
            let config = try XrayRunConfig.build(serverJson: prepared.json)
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
            // Автоподключение: iOS itself brings the VPN back (after a restart, sleep, network
            // change) until it is turned off by hand.
            m.onDemandRules = [NEOnDemandRuleConnect()]
            m.isOnDemandEnabled = UserDefaults.standard.bool(forKey: "autoconnect")
            state = .connecting
            try await m.saveToPreferences()
            // Saving the first time asks the user to allow the VPN; reload to get a usable manager.
            try await m.loadFromPreferences()
            manager = m
            observe()
            // Turned off (or paused for a ping) while the profile was being saved: stay off.
            guard wantOn else {
                if m.isOnDemandEnabled {
                    m.isOnDemandEnabled = false
                    try? await m.saveToPreferences()
                }
                // Автоподключение may have started it on the save already.
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
            // The profile as saved now (see connect). Turned off by hand: iOS must not bring it
            // back on its own.
            try? await m.loadFromPreferences()
            if m.isOnDemandEnabled {
                m.isOnDemandEnabled = false
                try? await m.saveToPreferences()
            }
            m.connection.stopVPNTunnel()
        }
    }

    /// What a profile's config was built from: the subscription's server as it was then.
    nonisolated static func source(_ server: Server) -> String {
        SHA256.hash(data: Data((server.xrayJson ?? "").utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// The subscription refreshed while off: iOS Settings and Автоподключение start the profile
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

    /// Автоподключение switched in the settings: applies to the VPN profile at once.
    func setAutoconnect(_ on: Bool) {
        guard let m = manager, isOn else { return }
        Task {
            try? await m.loadFromPreferences()
            m.isOnDemandEnabled = on
            try? await m.saveToPreferences()
        }
    }

    /// Turns the tunnel off for a ping: through it nearly every probe times out (the app's own
    /// traffic goes into the VPN). Автоподключение is held off so iOS doesn't bring it back
    /// meanwhile; `connect` restores it. Returns once the tunnel is down; false if it was off.
    func pauseForPing() async -> Bool {
        guard isOn, let m = manager else { return false }
        wantOn = false
        try? await m.loadFromPreferences()
        if m.isOnDemandEnabled {
            m.isOnDemandEnabled = false
            try? await m.saveToPreferences()
        }
        m.connection.stopVPNTunnel()
        for _ in 0..<50 {
            let status = m.connection.status
            if status == .disconnected || status == .invalid { break }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        // Routes and DNS of the network itself come back a moment later.
        try? await Task.sleep(nanoseconds: 700_000_000)
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
        manager?.connection.stopVPNTunnel()
        try? await Task.sleep(nanoseconds: 700_000_000)
        await connect(server)
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
