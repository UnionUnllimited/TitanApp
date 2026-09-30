import SwiftUI

@main
struct TitanVPSApp: App {
    @StateObject private var store = AppStore()
    @AppStorage("theme") private var theme = "system"

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .preferredColorScheme(theme == "dark" ? .dark : theme == "light" ? .light : nil)
                .tint(Palette.primary)
        }
    }
}

enum Palette {
    static let primary = Color(red: 0x1A / 255, green: 0x66 / 255, blue: 0xFF / 255)
    static let background = dynamic(light: 0xF2F4F8, dark: 0x0E131D)
    static let surface = dynamic(light: 0xFFFFFF, dark: 0x161C28)
    static let track = dynamic(light: 0xE8EBF1, dark: 0x1F2633)
    static let outline = dynamic(light: 0xD5DAE3, dark: 0x2C3444)
    static let green = dynamic(light: 0x12A150, dark: 0x22C55E)

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
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}
