import SwiftUI
import UIKit

/// A server's flag filled into a circle, as on Windows: the square flag from Flags.xcassets
/// (flag-icons, made in CI by scripts/download-flags-ios.sh; vector, sharp at any size), else
/// the flag emoji as large as the circle needs. No flag: a bolt for АВТО, else a server.
/// The app's list and status, Настройки and the Live Activity give it their colours.
struct FlagCircle: View {
    /// The name's leading flag emoji (String.splitFlag in the app).
    let flag: String?
    /// The name without it: АВТО gets the bolt.
    var name = ""
    let size: CGFloat
    /// Behind the bolt, the server and an emoji flag.
    var fill: Color = Color.secondary.opacity(0.15)
    /// A hairline round it: a white flag keeps its edge.
    var stroke: Color = Color.white.opacity(0.15)
    var bolt: Color = .yellow
    var symbol: Color = .secondary

    var body: some View {
        let code = Flags.code(flag)
        let country = code.flatMap(Flags.country)
        ZStack {
            if let code, let image = Flags.image(code) {
                // Original: not tinted as a button's template image.
                Image(image).renderingMode(.original).resizable().scaledToFill()
            } else {
                fill
                if let flag {
                    // The emoji's flag is wider than tall and has a margin: large enough that
                    // its height fills the circle.
                    Text(flag).font(.system(size: size * 1.6)).fixedSize()
                } else if name.lowercased().contains("авто") {
                    Image(systemName: "bolt.fill").font(.system(size: size * 0.5)).foregroundStyle(bolt)
                } else {
                    Image(systemName: "server.rack").font(.system(size: size * 0.5)).foregroundStyle(symbol)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(Circle().strokeBorder(stroke, lineWidth: size < 24 ? 0.5 : 1))
        // VoiceOver says the country; the bolt and the server add nothing to the name.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(country ?? "")
        .accessibilityHidden(country == nil)
    }
}

enum Flags {
    /// "🇫🇮" → "fi"; England's 🏴 with its tag letters → "gb-eng" (flag-icons' names).
    static func code(_ flag: String?) -> String? {
        guard let scalars = flag?.unicodeScalars.map(\.value) else { return nil }
        if scalars.count == 2, scalars.allSatisfy({ (0x1F1E6...0x1F1FF).contains($0) }) {
            return String(String.UnicodeScalarView(scalars.compactMap { Unicode.Scalar($0 - 0x1F1E6 + 0x61) }))
        }
        // 🏴, the subdivision in tag letters ("gbeng"), the cancel tag.
        guard scalars.count > 4, scalars.first == 0x1F3F4, scalars.last == 0xE007F else { return nil }
        let tags = scalars.dropFirst().dropLast()
        guard tags.allSatisfy({ (0xE0030...0xE0039).contains($0) || (0xE0061...0xE007A).contains($0) }) else { return nil }
        let s = String(String.UnicodeScalarView(tags.compactMap { Unicode.Scalar($0 - 0xE0000) }))
        return "\(s.prefix(2))-\(s.dropFirst(2))"
    }

    /// The flag's image in this build (a local one may go without Flags.xcassets); looked up
    /// once per flag.
    static func image(_ code: String) -> String? {
        let name = "flag-" + code
        lock.lock()
        defer { lock.unlock() }
        if let known = found[name] { return known ? name : nil }
        let has = UIImage(named: name) != nil
        found[name] = has
        return has ? name : nil
    }

    /// "Финляндия" from "fi"; nil for England and the like.
    static func country(_ code: String) -> String? {
        code.count == 2 ? russian.localizedString(forRegionCode: code.uppercased()) : nil
    }

    private static let russian = Locale(identifier: "ru_RU")
    private static let lock = NSLock()
    private static var found: [String: Bool] = [:]
}
