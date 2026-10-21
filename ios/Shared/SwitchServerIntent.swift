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
