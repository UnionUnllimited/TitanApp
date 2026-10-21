import AppIntents
import SwiftUI
import WidgetKit

/// Titan VPS on and off from Пункт управления, the Lock Screen and the Action button. The
/// toggle's intent runs here, in the widget extension, with the VPN profile the app saved
/// (TunnelSwitch): the app needn't be running.
struct VPNToggleControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: VPNControl.kind, provider: VPNStatusProvider()) { isOn in
            ControlWidgetToggle("Titan VPS", isOn: isOn, action: SetVPNIntent()) { on in
                Label(on ? "Подключено" : "Не подключено", systemImage: on ? "checkmark.shield.fill" : "shield")
            }
            .tint(Brand.green)
        }
        .displayName("Titan VPS")
        .description("Включает и выключает VPN на последнем сервере.")
    }
}

/// On: connected or connecting, as the profile's connection is now.
struct VPNStatusProvider: ControlValueProvider {
    var previewValue: Bool { false }

    func currentValue() async throws -> Bool {
        await TunnelSwitch.isOn()
    }
}

struct SetVPNIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Titan VPS"
    /// Siri and Команды have the app's own intents (VPNIntents.swift).
    static let isDiscoverable = false

    @Parameter(title: "Включён")
    var value: Bool

    func perform() async throws -> some IntentResult {
        // The toggle then shows what the VPN really did, failed or not.
        defer { VPNControl.reload() }
        if value { try await TunnelSwitch.start() } else { await TunnelSwitch.stop() }
        return .result()
    }
}
