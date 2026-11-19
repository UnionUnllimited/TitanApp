import AppIntents
import SwiftUI
import WidgetKit

/// Titan on and off from Пункт управления, the Lock Screen and the Action button. Its intent
/// (SetVPNIntent, ios/Shared) runs in the app's process; here the extension only reads the
/// VPN's state from the profile the app saved (TunnelSwitch).
struct VPNToggleControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: VPNControl.kind, provider: VPNStatusProvider()) { isOn in
            ControlWidgetToggle("Titan", isOn: isOn, action: SetVPNIntent()) { on in
                Label(on ? "В пути" : "Стоим", systemImage: on ? "location.north.line.fill" : "location.north.line")
            }
            .tint(Brand.green)
        }
        .displayName("Titan")
        .description("«Поехали» и «Остановиться» — по направлению, выбранному в приложении.")
    }
}

/// On: connected or connecting, as the profile's connection is now.
struct VPNStatusProvider: ControlValueProvider {
    var previewValue: Bool { false }

    func currentValue() async throws -> Bool {
        await TunnelSwitch.isOn()
    }
}
