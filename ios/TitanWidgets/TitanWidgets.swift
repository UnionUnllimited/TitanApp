import SwiftUI
import WidgetKit

/// The app's widget extension (iOS 18): the VPN toggle for Пункт управления, the Lock Screen and
/// the Action button, and the Live Activity (the VPN on or off).
@main
struct TitanWidgetsBundle: WidgetBundle {
    var body: some Widget {
        VPNToggleControl()
        VPNLiveActivity()
    }
}

/// The app's colours (Palette in the app). The Live Activity stays dark navy in light mode too.
enum Brand {
    static let navy = Color(hex: 0x0E131D)
    static let green = Color(hex: 0x22C55E)
    static let blue = Color(hex: 0x1A66FF)
    static let violet = Color(hex: 0x5B3DF5)
    /// The brand blue, lighter: readable on navy.
    static let sky = Color(hex: 0x7EA6FF)
    /// Выключено: the red of Выкл | Вкл while off.
    static let red = Color(hex: 0xEF4444)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}
