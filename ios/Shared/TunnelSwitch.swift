import Foundation
import Network
import NetworkExtension

/// Turns the VPN on and off without the app's screen: Пункт управления, Siri, Команды and the
/// Live Activity's buttons (their intents run in the app's process); TitanWidgets reads the
/// toggle's state with it. It works with the VPN profile the app saved when it last connected:
/// its providerConfiguration has that server's config, so the tunnel starts with no config in
/// the options, as it does from iOS Settings (only "by", which tells the tunnel the start is ours).
enum TunnelSwitch {
    /// A server to run instead of the profile's last one (the server picked in the app).
    struct Target {
        let name: String
        let provider: [String: Any]
    }

    static func manager() async throws -> NETunnelProviderManager {
        guard let m = try await NETunnelProviderManager.loadAllFromPreferences().first else {
            throw TunnelSwitchError("Откройте Titan VPS и один раз нажмите «Поехали» — после этого можно ехать и отсюда")
        }
        return m
    }

    static func isOn(_ status: NEVPNStatus) -> Bool {
        status == .connected || status == .connecting || status == .reasserting
    }

    /// Connected or connecting now; false with no profile yet.
    static func isOn() async -> Bool {
        guard let m = try? await manager() else { return false }
        return isOn(m.connection.status)
    }

    /// Same rule as in the app (ServerGroup.isBypass): bypass servers only over mobile internet.
    static func isBypass(_ name: String) -> Bool { name.lowercased().contains("обход") }

    /// Не отключать VPN's rules for a server: iOS starts it on any network; a bypass server
    /// (without Технический режим) only on mobile internet. Ignore leaves one already running
    /// on Wi-Fi be, so nothing drops.
    static func onDemandRules(_ name: String, bypassOnWifi: Bool) -> [NEOnDemandRule] {
        let connect = NEOnDemandRuleConnect()
        guard !bypassOnWifi, isBypass(name) else {
            connect.interfaceTypeMatch = .any
            return [connect]
        }
        connect.interfaceTypeMatch = .cellular
        let ignore = NEOnDemandRuleIgnore()
        ignore.interfaceTypeMatch = .any
        return [connect, ignore]
    }

    /// The server the profile runs, by its name in the subscription (with the flag).
    static func serverName() async -> String? {
        ((try? await manager())?.protocolConfiguration as? NETunnelProviderProtocol)?.serverAddress
    }

    /// When the VPN came up; nil unless it is connected now.
    static func connectedSince() async -> Date? {
        guard let m = try? await manager(), m.connection.status == .connected else { return nil }
        return m.connection.connectedDate ?? Date()
    }

    /// Starts the tunnel and waits until it is up; throws the tunnel's reason if it falls.
    /// Returns the server's name. Already on: nothing changes.
    /// onDemand: Не отключать VPN as set in the app (nil: as the profile has it).
    /// bypassOnWifi: Технический режим lets bypass servers run on Wi-Fi.
    @discardableResult
    static func start(_ target: Target? = nil, onDemand: Bool? = nil, bypassOnWifi: Bool = false) async throws -> String {
        let m = try await manager()
        guard let proto = m.protocolConfiguration as? NETunnelProviderProtocol else {
            throw TunnelSwitchError("Откройте Titan VPS и один раз нажмите «Поехали» — после этого можно ехать и отсюда")
        }
        let name = target?.name ?? proto.serverAddress ?? "Titan VPS"
        if isOn(m.connection.status) { return proto.serverAddress ?? name }
        if !bypassOnWifi, isBypass(name), await onWifi() {
            throw TunnelSwitchError("Объезды работают только через мобильный интернет. На Wi-Fi выберите другое направление в приложении")
        }
        var changed = false
        if let target {
            proto.serverAddress = target.name
            proto.providerConfiguration = target.provider
            m.protocolConfiguration = proto
            changed = true
        }
        if let onDemand, m.isOnDemandEnabled != onDemand {
            m.isOnDemandEnabled = onDemand
            noteSaved(onDemand)
            changed = true
        }
        // iOS turns our profile off when another VPN app connects.
        if !m.isEnabled {
            m.isEnabled = true
            changed = true
        }
        if changed {
            // On demand's rules for the server it runs now.
            m.onDemandRules = onDemandRules(name, bypassOnWifi: bypassOnWifi)
            try await m.saveToPreferences()
            try await m.loadFromPreferences()
        }
        // Turned off a moment ago: the old session ends first (a start meanwhile may be lost).
        for _ in 0..<15 {
            guard m.connection.status == .disconnecting else { break }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        // The tunnel takes the config from the profile; "by" tells it the start is ours, not iOS's
        // own switch (PacketTunnelProvider.giveOnDemandBack: on demand is set here). Не отключать
        // VPN may have started it on the save already.
        if !isOn(m.connection.status) { try m.connection.startVPNTunnel(options: ["by": "switch" as NSString]) }
        try await waitUntilUp(m)
        return name
    }

    /// Off, and on demand with it (turned off by hand: Не отключать VPN must not bring it back).
    static func stop() async {
        guard let m = try? await manager() else { return }
        if m.isOnDemandEnabled { await saveOnDemand(false, m) }
        m.connection.stopVPNTunnel()
    }

    /// The profile's on demand as the app last saved it (VPNController.switchedOffInSettings tells
    /// iOS Settings' «Подключать по запросу» by it). Noted before each save, so a save of ours
    /// is never taken for someone else's; in the app's own defaults (intents run in its process).
    static let savedKey = "onDemandSaved"

    static func noteSaved(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: savedKey)
    }

    /// On demand saved as [on], once more from the profile as it is now if that failed (a copy
    /// saved over meanwhile): an off that isn't saved has iOS start the VPN again at once. Never
    /// more than twice. False if neither took.
    @discardableResult
    static func saveOnDemand(_ on: Bool, _ m: NETunnelProviderManager) async -> Bool {
        noteSaved(on)
        m.isOnDemandEnabled = on
        if (try? await m.saveToPreferences()) != nil { return true }
        try? await m.loadFromPreferences()
        m.isOnDemandEnabled = on
        return (try? await m.saveToPreferences()) != nil
    }

    /// After stop, before start on another server: up to 5 s until the old session is down
    /// (start does nothing while it still counts as on).
    static func waitUntilDown() async {
        guard let m = try? await manager() else { return }
        for _ in 0..<25 {
            let status = m.connection.status
            if status == .disconnected || status == .invalid { return }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
    }

    /// Up to 12 s: enough for the tunnel to start, short enough for Siri. Still connecting
    /// after that is no error; never started (no network, say) is.
    private static func waitUntilUp(_ m: NETunnelProviderManager) async throws {
        let connection = m.connection
        var started = false
        var connecting = false
        for _ in 0..<60 {
            switch connection.status {
            case .connected: return
            case .connecting, .reasserting: started = true; connecting = true
            // Disconnecting too: start let the old session end, so this is the new one.
            case .disconnecting: started = true
            case .disconnected, .invalid:
                // It fell while starting: the tunnel's reason.
                if started {
                    if connecting { await giveUpOnDemand(m) }
                    throw await lastError(connection)
                }
            default: break
            }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        let status = connection.status
        if status == .disconnected || status == .invalid { throw await lastError(connection) }
    }

    /// Fell before it was ever up (a config the tunnel can't run, say): on demand would start it
    /// again and again, so it goes off, as VPNController.startFailed does.
    private static func giveUpOnDemand(_ m: NETunnelProviderManager) async {
        try? await m.loadFromPreferences()
        guard m.isOnDemandEnabled else { return }
        await saveOnDemand(false, m)
    }

    private static func lastError(_ connection: NEVPNConnection) async -> TunnelSwitchError {
        await withCheckedContinuation { cont in
            connection.fetchLastDisconnectError { error in
                cont.resume(returning: TunnelSwitchError(error.map { Self.routeMessage($0, inApp: false) ?? "Не удалось построить маршрут: \($0.localizedDescription)" }
                    ?? "Не удалось построить маршрут. Попробуйте другое направление"))
            }
        }
    }

    /// An off that came while the route was being built (Siri's or the activity's on, the
    /// tunnel's start).
    static let stoppedWhileStarting = "Стоим: поездку остановили, пока строили маршрут"

    /// The tunnel's own start errors (PacketTunnelProvider's, in its domain) that reach the app's
    /// screen, Siri and Пункт управления, in «Маршрут»'s words; nil for any other, which keeps
    /// its text after «Не удалось построить маршрут». By the tunnel's texts, which stay as they
    /// are (docs/HANDOFF.md, «Тема «Маршрут» на iOS»). inApp: on the app's own screen.
    static func routeMessage(_ error: Error, inApp: Bool) -> String? {
        let ns = error as NSError
        guard ns.domain == "com.titanvps.ios.tunnel" else { return nil }
        // Its declinedCode: iOS's own VPN switch turned it off (holdOnDemand).
        if ns.code == 2 { return "Стоим: VPN выключили в Настройках iOS" }
        switch ns.localizedDescription {
        case "Нет настроек сервера. Подключитесь из приложения":
            return inApp ? "Не удалось построить маршрут: нажмите «Поехали» ещё раз" : "Откройте Titan VPS и нажмите «Поехали»"
        case "VPN выключен":
            return stoppedWhileStarting
        case "Не удалось прочитать настройки сервера":
            return "Не удалось построить маршрут: ошибка в настройках направления. Попробуйте другое направление"
        default:
            return nil
        }
    }

    /// The phone is on Wi-Fi now (as AppStore.onWifi sees it).
    static func onWifi() async -> Bool {
        let monitor = NWPathMonitor()
        let once = Once()
        let wifi = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            monitor.pathUpdateHandler = { path in
                guard once.first() else { return }
                cont.resume(returning: path.status == .satisfied && path.availableInterfaces.contains { $0.type == .wifi })
            }
            monitor.start(queue: DispatchQueue(label: "com.titanvps.ios.switch.path"))
        }
        monitor.cancel()
        return wifi
    }

    /// The first path report only (they come on one serial queue).
    private final class Once {
        private var done = false
        func first() -> Bool {
            defer { done = true }
            return !done
        }
    }
}

struct TunnelSwitchError: LocalizedError, CustomLocalizedStringResourceConvertible {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
    /// What Siri and Пункт управления show.
    var localizedStringResource: LocalizedStringResource { LocalizedStringResource(stringLiteral: message) }
}
