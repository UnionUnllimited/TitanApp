import AppIntents
import Foundation

/// Siri and Команды: Titan VPS on, off and the other way round, without opening the app.
/// They run in the app's process (in the background if it isn't open) and work with the VPN
/// profile through TunnelSwitch, as Пункт управления's toggle does (SetVPNIntent); on comes on
/// the server picked in the app.
struct TurnOnVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Включить Titan VPS"
    static var description = IntentDescription("Подключает VPN к серверу, выбранному в приложении.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        if await TunnelSwitch.isOn() { return .result(dialog: "Titan VPS уже включён") }
        let name = try await VPNIntents.turnOn()
        return .result(dialog: "Titan VPS включён: \(name)")
    }
}

struct TurnOffVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Выключить Titan VPS"
    static var description = IntentDescription("Отключает VPN и автоподключение.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let wasOn = await TunnelSwitch.isOn()
        // Off anyway: Автоподключение goes off with it.
        await VPNIntents.turnOff()
        if wasOn { return .result(dialog: "Titan VPS выключен") }
        return .result(dialog: "Titan VPS уже выключен")
    }
}

struct ToggleVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Переключить Titan VPS"
    static var description = IntentDescription("Включает VPN, если он выключен, и выключает, если включён.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        if await TunnelSwitch.isOn() {
            await VPNIntents.turnOff()
            return .result(dialog: "Titan VPS выключен")
        }
        let name = try await VPNIntents.turnOn()
        return .result(dialog: "Titan VPS включён: \(name)")
    }
}

/// "Привет, Siri, включи Titan VPS" and the app's actions in Команды.
struct TitanShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: TurnOnVPNIntent(), phrases: [
            "Включи \(.applicationName)",
            "Включить \(.applicationName)",
            "Подключи \(.applicationName)",
            "Включи VPN в \(.applicationName)",
        ])
        AppShortcut(intent: TurnOffVPNIntent(), phrases: [
            "Выключи \(.applicationName)",
            "Выключить \(.applicationName)",
            "Отключи \(.applicationName)",
            "Выключи VPN в \(.applicationName)",
        ])
        AppShortcut(intent: ToggleVPNIntent(), phrases: [
            "Переключи \(.applicationName)",
            "Переключить \(.applicationName)",
        ])
    }
}

// Run in the background, these may start the Live Activity too (iOS 18+, as the activity).
@available(iOS 18.0, *)
extension TurnOnVPNIntent: LiveActivityIntent {}

@available(iOS 18.0, *)
extension ToggleVPNIntent: LiveActivityIntent {}

enum VPNIntents {
    /// Returns the server's name without its flag (for Siri to say).
    static func turnOn() async throws -> String {
        // Пункт управления's toggle then shows what the VPN really did, failed or not: run in the
        // background (Siri, Команды), the app has no screen to see the change.
        defer { VPNControl.reload() }
        let defaults = UserDefaults.standard
        let name = try await TunnelSwitch.start(picked(), onDemand: defaults.bool(forKey: "autoconnect"),
                                                bypassOnWifi: defaults.bool(forKey: TechMode.key))
        await LiveActivityController.turnedOn()
        return name.splitFlag.1
    }

    static func turnOff() async {
        defer { VPNControl.reload() }
        await TunnelSwitch.stop()
        await LiveActivityController.endAll()
    }

    /// The server picked in the app (AppStore keeps the subscription and the pick under these
    /// keys), with its config for the tunnel, built as VPNController.connect builds it.
    private static func picked() throws -> TunnelSwitch.Target {
        guard let data = UserDefaults.standard.data(forKey: "titan.subscription"),
              let sub = try? JSONDecoder().decode(Subscription.self, from: data) else {
            throw TunnelSwitchError("Добавьте ключ подписки в приложении Titan VPS")
        }
        let id = UserDefaults.standard.string(forKey: "titan.selected")
        guard let server = sub.servers.first(where: { $0.id == id }) ?? sub.servers.first else {
            throw TunnelSwitchError("В подписке нет серверов. Обновите её в приложении Titan VPS")
        }
        let prepared = try Plugins.prepare(server, forTunnel: true)
        var provider: [String: Any] = ["config": try XrayRunConfig.build(serverJson: prepared.json),
                                       "source": VPNController.source(server)]
        if let plugin = prepared.plugin { provider["plugin"] = plugin }
        return TunnelSwitch.Target(name: server.name, provider: provider)
    }
}
