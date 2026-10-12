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
        switch connection.status {
        case .connected: state = .connected(since: connection.connectedDate ?? Date())
        case .connecting, .reasserting: state = .connecting
        case .disconnecting: state = .disconnecting
        default: if case .error = state {} else { state = .off }
        }
    }

    func connect(_ server: Server) async {
        guard let json = server.xrayJson else {
            state = .error("Обновите подписку: потяните список серверов вниз")
            return
        }
        guard let tunnelId = Self.tunnelBundleId else {
            state = .error("В этой сборке нет VPN-расширения")
            return
        }
        do {
            let config = try XrayRunConfig.build(serverJson: json)
            let m = manager ?? NETunnelProviderManager()
            let proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
            proto.providerBundleIdentifier = tunnelId
            proto.serverAddress = server.name
            proto.providerConfiguration = ["config": config]
            proto.disconnectOnSleep = false
            m.protocolConfiguration = proto
            m.localizedDescription = "Titan VPS"
            m.isEnabled = true
            state = .connecting
            try await m.saveToPreferences()
            // Saving the first time asks the user to allow the VPN; reload to get a usable manager.
            try await m.loadFromPreferences()
            manager = m
            observe()
            try m.connection.startVPNTunnel(options: ["config": config as NSString])
        } catch {
            state = .error(Self.describe(error))
        }
    }

    func disconnect() {
        state = .disconnecting
        manager?.connection.stopVPNTunnel()
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
