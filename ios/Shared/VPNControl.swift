import WidgetKit

/// The Titan VPS toggle in Пункт управления, on the Lock Screen and the Action button
/// (TitanWidgets, iOS 18). The app and the toggle itself ask iOS to read its state again
/// whenever the VPN changes, so it doesn't stay on an old one.
enum VPNControl {
    static let kind = "com.titanvps.ios.vpn-toggle"

    static func reload() {
        if #available(iOS 18.0, *) { ControlCenter.shared.reloadControls(ofKind: kind) }
    }
}
