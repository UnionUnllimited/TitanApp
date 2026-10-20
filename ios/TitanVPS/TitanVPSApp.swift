import SwiftUI

@main
struct TitanVPSApp: App {
    @StateObject private var store = AppStore()
    @StateObject private var vpn = VPNController()
    @AppStorage("theme") private var theme = "system"
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(vpn)
                .onAppear { store.vpn = vpn }
                .preferredColorScheme(theme == "dark" ? .dark : theme == "light" ? .light : nil)
                .tint(Palette.primary)
                .onChange(of: theme) { AppIconSwitcher.schedule(theme: $0) }
                // Left the app before the icon could change: change it on return.
                .onChange(of: scenePhase) { if $0 == .active { AppIconSwitcher.applyDeferred() } }
        }
    }
}

/// The home-screen icon follows the app's theme: Светлая and Тёмная have their own icon;
/// Системная is the main one, which iOS 18 itself shows dark (or tinted) with the system.
/// iOS shows its own "icon changed" alert each time; quick taps on the theme button make
/// one change, a moment after the last tap (a change while one is running would fail).
enum AppIconSwitcher {
    private static var pending: DispatchWorkItem?
    private static var deferred: String?

    static func schedule(theme: String) {
        pending?.cancel()
        let work = DispatchWorkItem { AppIconSwitcher.apply(theme: theme) }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2, execute: work)
    }

    static func applyDeferred() {
        guard let theme = deferred else { return }
        deferred = nil
        schedule(theme: theme)
    }

    static func apply(theme: String) {
        let app = UIApplication.shared
        guard app.supportsAlternateIcons else { return }
        guard app.applicationState == .active else { deferred = theme; return }
        deferred = nil
        let name: String? = theme == "dark" ? "AppIconDark" : theme == "light" ? "AppIconLight" : nil
        guard app.alternateIconName != name else { return }
        app.setAlternateIconName(name) { _ in }
    }
}

enum Palette {
    static let primary = Color(red: 0x1A / 255, green: 0x66 / 255, blue: 0xFF / 255)
    static let background = dynamic(light: 0xF2F4F8, dark: 0x0E131D)
    static let surface = dynamic(light: 0xFFFFFF, dark: 0x161C28)
    static let track = dynamic(light: 0xE8EBF1, dark: 0x1F2633)
    static let outline = dynamic(light: 0xD5DAE3, dark: 0x2C3444)
    static let green = dynamic(light: 0x12A150, dark: 0x22C55E)
    /// The logo's blue to violet: main buttons, the picked theme.
    static let violet = Color(red: 0x5B / 255, green: 0x3D / 255, blue: 0xF5 / 255)
    static let cyan = Color(red: 0x00 / 255, green: 0xC8 / 255, blue: 0xFF / 255)
    /// The logo's colours across a title.
    static let logoGradient = LinearGradient(colors: [cyan, primary, violet], startPoint: .leading, endPoint: .trailing)
    static let accentGradient = LinearGradient(colors: [primary, violet], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let greenGradient = LinearGradient(colors: [Color(UIColor(hex: 0x16A34A)), Color(UIColor(hex: 0x15803D))],
                                              startPoint: .topLeading, endPoint: .bottomTrailing)
    /// The Выкл | Вкл knob while off: raised above the track.
    static let knob = dynamic(light: 0xFFFFFF, dark: 0x343D4F)

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        // Clipped: a pressed row's highlight keeps the rounded corners.
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface, in: shape)
            .clipShape(shape)
    }
}
