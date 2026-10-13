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
    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?

    init() {
        Task { await load() }
    }

    var isOn: Bool {
        switch state { case .connecting, .connected: return true; default: return false }
    }

    /// The extension's bundle id as it is in this build (a re-signing service may change it).
    private static var tunnelBundleId: String? {
        guard let plugins = Bundle.main.builtInPlugInsURL,
              let items = try? FileManager.default.contentsOfDirectory(at: plugins, includingPropertiesForKeys: nil) else { return nil }
        return items.first { $0.pathExtension == "appex" }.flatMap { Bundle(url: $0)?.bundleIdentifier }
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
        guard let tunnelId = Self.tunnelBundleId else {
            state = .error("В этой сборке нет VPN-расширения")
            return
        }
        do {
            // MASQUE rewritten for Xray's own client; TUIC / AnyTLS / ShadowTLS / SSH / Mieru
            // pointed at their client, which the tunnel starts first; Naive says it can't.
            let prepared = try Plugins.prepare(server, forTunnel: true)
            let config = try XrayRunConfig.build(serverJson: prepared.json)
            let m = manager ?? NETunnelProviderManager()
            let proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
            proto.providerBundleIdentifier = tunnelId
            proto.serverAddress = server.name
            var provider: [String: Any] = ["config": config]
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
            var options: [String: NSObject] = ["config": config as NSString]
            if let plugin = prepared.plugin { options["plugin"] = plugin as NSString }
            try m.connection.startVPNTunnel(options: options)
        } catch {
            state = .error(Self.describe(error))
        }
    }

    func disconnect() {
        state = .disconnecting
        guard let m = manager else { return }
        // Turned off by hand: iOS must not bring it back on its own.
        if m.isOnDemandEnabled {
            m.isOnDemandEnabled = false
            Task {
                try? await m.saveToPreferences()
                m.connection.stopVPNTunnel()
            }
        } else {
            m.connection.stopVPNTunnel()
        }
    }

    /// Автоподключение switched in the settings: applies to the VPN profile at once.
    func setAutoconnect(_ on: Bool) {
        guard let m = manager, isOn else { return }
        m.isOnDemandEnabled = on
        Task { try? await m.saveToPreferences() }
    }

    /// Reconnects on another server while on.
    func switchTo(_ server: Server) async {
        guard isOn else { return }
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
