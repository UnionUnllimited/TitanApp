import AppIntents
import Foundation

/// The Live Activity's ‹ and › (VPNLiveActivity in TitanWidgets): the previous or the next
/// server of the list in the app. In both targets, as SetVPNIntent: a LiveActivityIntent runs in
/// the app's process (launched in the background if need be), which switches the VPN there.
@available(iOS 18.0, *)
struct SwitchServerIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Сменить сервер"
    /// Only the activity's buttons: not for Siri and Команды.
    static let isDiscoverable = false

    @Parameter(title: "Вперёд")
    var forward: Bool

    init() {}

    init(forward: Bool) {
        self.forward = forward
    }

    func perform() async throws -> some IntentResult {
        #if TITAN_APP
        await VPNIntents.switchServer(forward: forward)
        #endif
        // The extension's copy never runs: iOS runs the app's.
        return .result()
    }
}

/// The Dynamic Island's ⏻: the VPN off, as Siri's «Выключить» (Автоподключение off too, the
/// activity closes). A LiveActivityIntent in both targets, so it runs in the app's process.
@available(iOS 18.0, *)
struct StopVPNIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Выключить Titan VPS"
    /// Only the activity's button: Siri and Команды have TurnOffVPNIntent.
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        #if TITAN_APP
        await VPNIntents.turnOff()
        #endif
        // The extension's copy never runs: iOS runs the app's.
        return .result()
    }
}
