import AppIntents
import Foundation

/// The action of the toggle in Пункт управления, on the Lock Screen and the Action button
/// (VPNToggleControl in TitanWidgets). In both targets: as a LiveActivityIntent iOS runs it in
/// the app's process (launching it in the background if need be), which may start and end the
/// Live Activity there and knows the app's settings.
@available(iOS 18.0, *)
struct SetVPNIntent: SetValueIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Titan VPS"
    /// Siri and Команды have the app's own intents (VPNIntents.swift).
    static let isDiscoverable = false

    @Parameter(title: "Включён")
    var value: Bool

    func perform() async throws -> some IntentResult {
        // The toggle then shows what the VPN really did, failed or not.
        defer { VPNControl.reload() }
        #if TITAN_APP
        // As Siri does: the server picked in the app, Не отключать VPN, the Live Activity.
        if value { _ = try await VPNIntents.turnOn() } else { await VPNIntents.turnOff() }
        #else
        // The extension's copy (iOS runs it in the app): the profile as the app saved it.
        if value { try await TunnelSwitch.start() } else { await TunnelSwitch.stop() }
        #endif
        return .result()
    }
}
