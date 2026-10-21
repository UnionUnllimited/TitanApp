import WidgetKit

/// The Titan VPS toggle in Пункт управления, on the Lock Screen and the Action button
/// (TitanWidgets, iOS 18). The app asks iOS to read its state again whenever it sees the VPN
/// change, each time it is opened and after the toggle, Siri or Команды turn it on or off, so
/// it doesn't stay on an old one. Not the tunnel: no WidgetKit in its ~50 MB.
enum VPNControl {
    static let kind = "com.titanvps.ios.vpn-toggle"

    static func reload() {
        if #available(iOS 18.0, *) { ControlCenter.shared.reloadControls(ofKind: kind) }
    }
}
