import SwiftUI
import UIKit

/// Маршрут's own colours (design C «Чистый маршрут», docs/HANDOFF.md), light and dark. Text on
/// them meets AA contrast: the system's orange, red and the app's green don't on white at
/// small sizes, so these are deeper in light and lighter in dark.
enum RouteTone {
    /// The map's ground (RouteMap's picture), behind the status bar too.
    static let paper = dynamic(0xF3F1EC, 0x0F1420)
    /// The list's sheet and its rows; dark as the app's surface (Профиль's cards).
    static let sheet = dynamic(0xFFFFFF, 0x161C28)
    /// The picked route's card on the sheet.
    static let well = dynamic(0xF4F5F8, 0x1F2633)
    /// The picked row of the list: the brand's tint, opaque (a swipe's action is under it).
    static let picked = dynamic(0xEEF3FF, 0x1B2540)
    static let ink2 = dynamic(0x535B6B, 0xA3AAB8)
    /// Deep enough for AA on the picked row, the wells and the glass, not only on white.
    static let ink3 = dynamic(0x5D6475, 0x8E95A5)
    /// The brand blue as text: lighter on dark.
    static let accentInk = dynamic(0x1A66FF, 0x8AB0FF)
    /// The brand blue as text on its own light tint (Проверить дороги, the picked tab on its
    /// tinted thumb): accentInk is under AA there in light, so deeper.
    static let accentInkOnTint = dynamic(0x1450D0, 0x8AB0FF)
    /// The route chip on the trip card («✦ Лучший маршрут»).
    static let chipInk = dynamic(0x2A46D8, 0x9DB6FF)
    static let chipFill = dynamic(0xEEF1FF, 0x1B2747)
    static let hair = dynamic(UIColor(white: 0, alpha: 0.08), UIColor(white: 1, alpha: 0.1))
    /// Traffic on the way: a ping chip's text, its dot and its fill.
    static let good = dynamic(0x127A43, 0x30D158)
    static let goodDot = dynamic(0x12B76A, 0x30D158)
    static let goodFill = dynamic(0xE3F6EA, 0x1A3930)
    static let mid = dynamic(0x8A5300, 0xFFD60A)
    static let midDot = dynamic(0xF5A400, 0xFFD60A)
    static let midFill = dynamic(0xFFF3D6, 0x373624)
    static let bad = dynamic(0xB42318, 0xFF6B60)
    static let badDot = dynamic(0xF04438, 0xFF6B60)
    static let badFill = dynamic(0xFDE7E5, 0x3B232B)
    /// Red text (an error, «Остановиться»), and the red its glass is tinted with.
    static let danger = dynamic(0xB42318, 0xFF6B60)
    static let dangerTint = dynamic(0xF04438, 0xFF453A)
    /// Orange text: a pause, a limited bypass, a note about Wi-Fi.
    static let warn = dynamic(0xB45309, 0xFFB340)
    /// The account's notices on the map: white text on them in both themes.
    static let alarmFill = dynamic(0xB42318, 0xB42318)
    static let noticeFill = dynamic(0xB45309, 0xB45309)
    /// A favourite's star: deep enough to be seen on white.
    static let star = dynamic(0xC27C00, 0xFFD60A)
    /// The unlimited row's bolt and its tile.
    static let bolt = dynamic(0xE08A00, 0xFFC233)
    static let boltFill = dynamic(0xFFF4DB, 0x3A2E12)
    /// The road the route runs on (the map's main road): the route's casing.
    static let casing = dynamic(0xFFFFFF, 0x2A3346)

    private static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }

    private static func dynamic(_ light: UIColor, _ dark: UIColor) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }
}

/// A server's Пинг in words, as traffic on the road. It is a cold delay (a fresh connection
/// through the server, XrayPing), so the list's own thresholds (500 and 1500 ms, its colours
/// all along), not the warm latency's 120 and 300 (Latency, on the trip card): with those
/// nearly every working server would read «пробки».
enum RouteTraffic {
    enum Level { case free, dense, jam, timeout }

    static func level(_ ms: Int?) -> Level? {
        guard let ms else { return nil }
        if ms < 0 { return .timeout }
        if ms < 500 { return .free }
        if ms < 1500 { return .dense }
        return .jam
    }

    /// Nothing while not measured or timed out (the chip says «таймаут»).
    static func word(_ ms: Int?) -> String? {
        switch level(ms) {
        case .free: return "свободно"
        case .dense: return "плотное движение"
        case .jam: return "пробки"
        case .timeout, nil: return nil
        }
    }
}

/// A server's Пинг as a tinted chip (design C): «85 мс» green, orange or red as on the road,
/// «таймаут» red, a speedometer while not measured, a spinner while measuring (over the old
/// value, dimmed).
struct PingChip: View {
    let ping: Int?
    let pending: Bool

    var body: some View {
        let level = RouteTraffic.level(ping)
        HStack(spacing: 6) {
            if pending {
                ProgressView().scaleEffect(0.6).frame(width: 12, height: 12)
            } else if level != nil {
                Circle().fill(Self.dot(level)).frame(width: 6, height: 6)
            }
            if let ping {
                Text(ping >= 0 ? "\(ping) мс" : "таймаут").monospacedDigit().opacity(pending ? 0.5 : 1)
            } else if !pending {
                Image(systemName: "speedometer")
            }
        }
        .font(.scaled(13, weight: .semibold))
        .lineLimit(1)
        .foregroundStyle(Self.ink(level))
        .padding(.horizontal, 10)
        .frame(minHeight: 26)
        .background(Self.fill(level), in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(pending ? "Пинг: измеряем" : ping.map { $0 >= 0 ? "Пинг \($0) миллисекунд" : "Пинг: таймаут" } ?? "Пинг не измерен")
    }

    private static func ink(_ level: RouteTraffic.Level?) -> Color {
        switch level {
        case .free: return RouteTone.good
        case .dense: return RouteTone.mid
        case .jam, .timeout: return RouteTone.bad
        case nil: return RouteTone.ink3
        }
    }

    private static func dot(_ level: RouteTraffic.Level?) -> Color {
        switch level {
        case .free: return RouteTone.goodDot
        case .dense: return RouteTone.midDot
        case .jam, .timeout: return RouteTone.badDot
        case nil: return RouteTone.ink3
        }
    }

    private static func fill(_ level: RouteTraffic.Level?) -> Color {
        switch level {
        case .free: return RouteTone.goodFill
        case .dense: return RouteTone.midFill
        case .jam, .timeout: return RouteTone.badFill
        case nil: return Glass.well
        }
    }
}

/// A server's sign in the list and on the picked route's card (design C's tiles): «Лучший
/// маршрут» sparkles on the brand gradient, the unlimited row's bolt on amber, else its round
/// flag (FlagCircle: VoiceOver says the country) on a light tile.
struct RouteTile: View {
    /// The whole name, flag and all.
    let server: String
    var size: CGFloat = 40

    var body: some View {
        let parts = server.splitFlag
        let shape = RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
        if parts.0 == nil && RouteName.isBest(parts.1) {
            Image(systemName: "sparkles")
                .font(.system(size: size * 0.44, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(Palette.accentGradient, in: shape)
                .accessibilityHidden(true)
        } else if parts.0 == nil && UnlimitedServer.isNamed(parts.1) {
            Image(systemName: "bolt.fill")
                .font(.system(size: size * 0.44, weight: .semibold))
                .foregroundStyle(RouteTone.bolt)
                .frame(width: size, height: size)
                .background(RouteTone.boltFill, in: shape)
                .accessibilityHidden(true)
        } else {
            FlagCircle(server: server, size: size * 0.68)
                .frame(width: size, height: size)
                .background(RouteTone.well, in: shape)
        }
    }

    /// The name as a tile's row shows it: the unlimited one without its ⚡, which the tile draws.
    static func title(_ server: String) -> String {
        let name = server.splitFlag.1
        let shown = RouteName.shown(name)
        guard UnlimitedServer.isNamed(name), shown.hasPrefix("⚡ ") else { return shown }
        return String(shown.dropFirst(2))
    }
}

/// «Поехали» and «Остановиться»: what the Стоим | В пути slider did, as one wide pill (design
/// C), with its rules. A tap asks onToggle: false gives the knob's old shake and an error tap;
/// true a firm tap, and the way shown at once (Строим маршрут…, Останавливаемся…) until the
/// VPN's state follows, 1.5 s at most. Building the route a tap stops it; stopping, a tap goes
/// again. A failed connect (failures) shakes it without a haptic: VPNController's own byUser
/// outcome is felt. While a ping runs it is locked: grey, a lock and a shine; a tap shakes it
/// and says why. The way in is the screen's one solid gradient button, the way out red tinted
/// glass. Its shadow keeps within 16 pt, the sheet's and the card's padding.
struct RoutePill: View {
    enum Phase: Equatable { case off, connecting, on, disconnecting }

    let phase: Phase
    var locked = false
    var lockedText = "Пинг…"
    /// Failed connect attempts so far: a new one after a tap shakes it.
    var failures = 0
    /// Tapped by the user: true to go. False back means it can't be done now.
    let onToggle: (Bool) -> Bool
    var onLockedTap: () -> Void = {}

    /// The phase a tap asked for, shown until the VPN's state follows.
    @State private var pending: Phase?
    @State private var shakes: CGFloat = 0
    @Environment(\.colorScheme) private var scheme

    private var shown: Phase { pending ?? phase }
    private var isOn: Bool { shown == .on || shown == .connecting }
    /// The way out: red glass; the way in: the gradient.
    private var stopping: Bool { shown == .on || shown == .disconnecting }

    var body: some View {
        Button(action: tapped) {
            surface(label
                .font(.scaled(19, weight: .semibold))
                .lineLimit(1)
                // «Останавливаемся…» on a 320 pt screen at large text.
                .minimumScaleFactor(0.7)
                .foregroundStyle(stopping ? RouteTone.danger : Color.white)
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, minHeight: 58))
                .saturation(locked ? 0.15 : 1)
                .shimmer(locked, tint: Color.white.opacity(0.45), duration: 1.4)
                .contentShape(Capsule())
        }
        .buttonStyle(PressableStyle(scale: 0.97))
        .modifier(ShakeEffect(animatableData: shakes))
        .animation(.easeInOut(duration: 0.25), value: shown)
        .animation(.easeInOut(duration: 0.35), value: locked)
        .onChange(of: phase) { _ in pending = nil }
        .onChange(of: failures) { _ in
            guard pending != nil else { return }
            withAnimation(.spring(response: 0.38, dampingFraction: 0.78)) { pending = nil }
            // The shake only: the failed connect is felt from VPNController (the user's own ask).
            refuse(haptic: false)
        }
        .task(id: pending) {
            guard pending != nil else { return }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled { pending = nil }
        }
        // As the slider said it: what it does, and where we are.
        .accessibilityLabel(locked ? "Маршрут" : title)
        .accessibilityValue(locked ? "недоступно, идёт пинг" : isOn ? "в пути" : "стоим")
        .accessibilityHint(!locked && shown == .connecting ? "Ещё раз — остановиться" : "")
    }

    private var title: String {
        switch shown {
        case .off: return "Поехали"
        case .connecting: return "Строим маршрут…"
        case .on: return "Остановиться"
        case .disconnecting: return "Останавливаемся…"
        }
    }

    private var label: some View {
        HStack(spacing: 10) {
            if locked {
                Image(systemName: "lock.fill")
                Text(lockedText)
            } else {
                switch shown {
                case .off:
                    Image(systemName: "location.north.line.fill")
                case .connecting:
                    ProgressView().tint(Color.white)
                case .on:
                    Image(systemName: "stop.fill")
                case .disconnecting:
                    ProgressView().tint(RouteTone.danger)
                }
                Text(title)
                if shown == .connecting {
                    // A tap now stops it.
                    Image(systemName: "xmark")
                        .font(.scaled(12, weight: .bold))
                        .frame(width: 26, height: 26)
                        .background(Color.white.opacity(0.22), in: Circle())
                }
            }
        }
    }

    @ViewBuilder private func surface<V: View>(_ content: V) -> some View {
        let shape = Capsule()
        if stopping {
            content.tintedGlass(RouteTone.dangerTint, in: shape)
        } else {
            content
                .background(Palette.accentGradient, in: shape)
                .overlay(shape.strokeBorder(Color.white.opacity(0.22), lineWidth: 1).allowsHitTesting(false))
                .shadow(color: scheme == .dark ? .clear : Palette.primary.opacity(0.3), radius: 12, y: 6)
        }
    }

    private func tapped() {
        if locked { refuse(); onLockedTap(); return }
        commit(!isOn)
    }

    private func commit(_ on: Bool) {
        if onToggle(on) {
            Haptics.tap(.medium)
            withAnimation(.spring(response: 0.38, dampingFraction: 0.78)) { pending = on ? .connecting : .disconnecting }
        } else {
            refuse()
        }
    }

    private func refuse(haptic: Bool = true) {
        if haptic { Haptics.error() }
        withAnimation(.linear(duration: 0.4)) { shakes += 1 }
    }
}

/// The trip card's clock, HH:MM:SS since the VPN came up: only this redraws each second.
struct TripTimer: View {
    let since: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let s = max(Int(ctx.date.timeIntervalSince(since)), 0)
            Text(String(format: "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60))
                .font(.scaled(46, weight: .bold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .accessibilityLabel("Время в пути")
                .accessibilityValue(Self.spoken(s))
        }
    }

    private static func spoken(_ s: Int) -> String {
        let h = s / 3600, m = s % 3600 / 60, sec = s % 60
        return (h > 0 ? "\(h) ч " : "") + "\(m) мин \(sec) с"
    }
}

/// The trip card's three tiles, as a navigator says them (the owner's words): «Скорость» to
/// you and «Обратно» from you now, in Мбит/с (Fmt.megabits; the Live Activity keeps its МБ/с),
/// and «Дорога»: how free the way is by the latency the last check measured (Latency: a warm
/// connection's, not a server's Пинг): «свободна» under 120 ms, «плотная» under 300, «пробки»
/// from 300, «—» while it isn't known; the number under it only under 300 ms, as everywhere.
/// TrafficMonitor is observed here only: a second's tick redraws these, not Маршрут.
struct TripStats: View {
    @ObservedObject var monitor: TrafficMonitor
    let check: ConnectionCheck.Status

    var body: some View {
        // Side by side; one under the other where three don't fit (320 pt, large text).
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 10) {
                down(row: false)
                divider
                up(row: false)
                divider
                road(row: false)
            }
            VStack(spacing: 10) {
                down(row: true)
                up(row: true)
                road(row: true)
            }
        }
    }

    private var divider: some View {
        Rectangle().fill(RouteTone.hair).frame(width: 1, height: 52)
    }

    private func down(row: Bool) -> some View {
        let value = Fmt.megabits(monitor.down)
        return tile(row: row, icon: "arrow.down", tint: RouteTone.accentInk, caption: "Скорость",
                    value: Text(value), unit: "Мбит/с", note: "к вам")
            .accessibilityLabel("Скорость к вам: \(value) мегабит в секунду")
    }

    private func up(row: Bool) -> some View {
        let value = Fmt.megabits(monitor.up)
        return tile(row: row, icon: "arrow.up", tint: RouteTone.chipInk, caption: "Обратно",
                    value: Text(value), unit: "Мбит/с", note: "от вас")
            .accessibilityLabel("Обратно, от вас: \(value) мегабит в секунду")
    }

    private func road(row: Bool) -> some View {
        let r = Self.road(check)
        return tile(row: row, icon: "car.fill", tint: r.color, caption: "Дорога",
                    value: Text(r.word).foregroundColor(r.color), unit: nil, note: r.note, busy: check == .checking)
            .accessibilityLabel(check == .checking ? "Дорога: проверяем" : "Дорога: \(r.spoken)")
    }

    /// The road by the check: its word, colour and the note under it.
    private static func road(_ check: ConnectionCheck.Status) -> (word: String, color: Color, note: String, spoken: String) {
        switch check {
        case .checking:
            return ("—", RouteTone.ink3, "проверяем", "проверяем")
        case .ok(let r):
            guard let ms = r.ms, ms >= 0 else { return ("—", RouteTone.ink3, "не измерено", "едем, задержка не измерена") }
            if ms < Latency.good { return ("свободна", RouteTone.good, "\(ms) мс", "свободна, \(ms) миллисекунд") }
            if ms < Latency.calm { return ("плотная", RouteTone.mid, "\(ms) мс", "плотная, \(ms) миллисекунд") }
            return ("пробки", RouteTone.bad, "\(Latency.calm)+ мс", "пробки, больше \(Latency.calm) миллисекунд")
        case .failed:
            return ("—", RouteTone.ink3, "нет ответа", "маршрут не отвечает")
        case .idle:
            return ("—", RouteTone.ink3, "не проверяли", "ещё не проверяли")
        }
    }

    /// Caption, value and the small note under it; row: caption and note on the left, the value
    /// on the right (one under the other).
    @ViewBuilder private func tile(row: Bool, icon: String, tint: Color, caption: String, value: Text,
                                   unit: String?, note: String, busy: Bool = false) -> some View {
        let head = HStack(spacing: 5) {
            Image(systemName: icon).font(.scaled(11, weight: .bold)).foregroundStyle(tint)
            Text(caption).font(.scaled(12, weight: .semibold)).foregroundStyle(RouteTone.ink2)
        }
        let number = HStack(alignment: .firstTextBaseline, spacing: 3) {
            if busy {
                ProgressView().scaleEffect(0.8).frame(height: 22)
            } else {
                value.font(.scaled(19, weight: .bold).monospacedDigit())
                if let unit { Text(unit).font(.scaled(12, weight: .semibold)).foregroundStyle(RouteTone.ink2) }
            }
        }
        let small = Text(note).font(.scaled(12, weight: .medium)).foregroundStyle(RouteTone.ink3)
        Group {
            if row {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 1) { head; small }
                    Spacer(minLength: 8)
                    number
                }
            } else {
                VStack(alignment: .leading, spacing: 3) { head; number; small }
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .lineLimit(1)
        .accessibilityElement(children: .ignore)
    }
}
