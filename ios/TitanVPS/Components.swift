import SwiftUI
import UIKit

/// Taps the phone answers with: a switch moved, a choice made, something done or refused.
enum Haptics {
    static func tap(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }
    static func selection() { UISelectionFeedbackGenerator().selectionChanged() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

// MARK: - Buttons

/// Any custom button: shrinks a little while pressed.
struct PressableStyle: ButtonStyle {
    var scale: CGFloat = 0.96
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// The main action on a screen: the brand gradient, white text, a soft glow.
struct PrimaryButtonStyle: ButtonStyle {
    var height: CGFloat = 54
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: height)
            .padding(.horizontal, 16)
            .background(Palette.accentGradient, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(0.18), lineWidth: 1))
            .shadow(color: Palette.primary.opacity(isEnabled ? 0.35 : 0), radius: 12, y: 6)
            .opacity(isEnabled ? 1 : 0.5)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// A second action next to the main one: tinted background, coloured text.
struct SecondaryButtonStyle: ButtonStyle {
    var height: CGFloat = 50
    var tint: Color = Palette.primary
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity, minHeight: height)
            .padding(.horizontal, 12)
            .background(tint.opacity(configuration.isPressed ? 0.2 : 0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .opacity(isEnabled ? 1 : 0.5)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// A row in a card: highlighted while pressed, like a list row.
struct RowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Palette.track : Color.clear)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

/// A round icon button (refresh, support…) on a surface.
struct CircleIconButton: View {
    let icon: String
    var spinning = false
    var size: CGFloat = 40
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            SpinningIcon(name: icon, spinning: spinning)
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(Palette.primary)
                .frame(width: size, height: size)
                .background(Palette.primary.opacity(0.12), in: Circle())
        }
        .buttonStyle(PressableStyle(scale: 0.9))
    }
}

/// An SF Symbol that keeps turning while something loads.
struct SpinningIcon: View {
    let name: String
    let spinning: Bool

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: !spinning)) { ctx in
            let turn = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9
            Image(systemName: name)
                .rotationEffect(.degrees(spinning ? turn * 360 : 0))
        }
    }
}

// MARK: - Toggle

/// Автоподключение and other switches: a green capsule with a mark on the knob.
struct TitanToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 12) {
            configuration.label
            Spacer(minLength: 8)
            ZStack(alignment: configuration.isOn ? .trailing : .leading) {
                Capsule()
                    .fill(configuration.isOn ? AnyShapeStyle(Palette.greenGradient) : AnyShapeStyle(Palette.outline))
                    .frame(width: 54, height: 32)
                Circle()
                    .fill(Color.white)
                    .frame(width: 26, height: 26)
                    .shadow(color: .black.opacity(0.2), radius: 3, y: 1.5)
                    .overlay(
                        Image(systemName: configuration.isOn ? "checkmark" : "xmark")
                            .font(.system(size: 10, weight: .heavy))
                            .foregroundStyle(configuration.isOn ? Palette.green : Color.gray)
                    )
                    .padding(3)
            }
            .animation(.spring(response: 0.3, dampingFraction: 0.75), value: configuration.isOn)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            Haptics.tap()
            configuration.isOn.toggle()
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(configuration.isOn ? "включено" : "выключено")
        .accessibilityAction { configuration.isOn.toggle() }
    }
}

// MARK: - Settings rows

/// A white symbol on a coloured rounded square, as in iOS Settings.
struct IconTile: View {
    let icon: String
    let color: Color
    var size: CGFloat = 32

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: size * 0.47, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(
                LinearGradient(colors: [color.opacity(0.8), color], startPoint: .top, endPoint: .bottom),
                in: RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
            )
    }
}

struct SettingsRow<Trailing: View>: View {
    let icon: String
    let color: Color
    let title: String
    var subtitle: String? = nil
    var titleColor: Color = .primary
    var chevron = false
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 14) {
            IconTile(icon: icon, color: color)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .medium)).foregroundStyle(titleColor)
                if let subtitle {
                    Text(subtitle).font(.system(size: 12)).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            trailing
            if chevron {
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(minHeight: 58)
        .contentShape(Rectangle())
    }
}

extension SettingsRow where Trailing == EmptyView {
    init(icon: String, color: Color, title: String, subtitle: String? = nil, titleColor: Color = .primary, chevron: Bool = false) {
        self.init(icon: icon, color: color, title: title, subtitle: subtitle, titleColor: titleColor, chevron: chevron) { EmptyView() }
    }
}

/// "ОФОРМЛЕНИЕ" above a card.
struct SectionTitle: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 13, weight: .semibold))
            .kerning(0.6)
            .foregroundStyle(.secondary)
            .padding(.leading, 6)
            .padding(.top, 14)
    }
}

/// The divider between rows of a card, under the text (not the icon).
struct RowDivider: View {
    var inset: CGFloat = 62
    var body: some View { Divider().padding(.leading, inset) }
}

// MARK: - Progress

/// Траффик and such: a rounded bar with the brand gradient.
struct GradientBar: View {
    let value: Double
    var gradient: LinearGradient = Palette.accentGradient

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.track)
                Capsule().fill(gradient)
                    .frame(width: max(g.size.width * min(max(value, 0), 1), value > 0 ? 8 : 0))
            }
        }
        .frame(height: 8)
        .animation(.easeOut(duration: 0.4), value: value)
    }
}

/// How much of a ping run is done, as a ring.
struct ProgressRing: View {
    let progress: Double
    var color: Color = .white

    var body: some View {
        ZStack {
            Circle().stroke(color.opacity(0.3), lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: max(min(progress, 1), 0.04))
                .stroke(color, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .animation(.easeOut(duration: 0.3), value: progress)
    }
}

/// A light band running across the view (a shine on something busy).
struct Shimmer: ViewModifier {
    var active = true
    var duration = 1.6
    var tint: Color = .white
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.overlay {
            if active && !reduceMotion {
                TimelineView(.animation) { ctx in
                    let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: duration) / duration
                    GeometryReader { g in
                        LinearGradient(colors: [tint.opacity(0), tint.opacity(0.55), tint.opacity(0)],
                                       startPoint: .leading, endPoint: .trailing)
                            .frame(width: g.size.width * 0.45)
                            .offset(x: (CGFloat(t) * 1.9 - 0.45) * g.size.width)
                    }
                }
                .mask { content }
                .allowsHitTesting(false)
            }
        }
    }
}

extension View {
    func shimmer(_ active: Bool = true, tint: Color = .white, duration: Double = 1.6) -> some View {
        modifier(Shimmer(active: active, duration: duration, tint: tint))
    }
}

/// A short shake left and right (a refused tap).
struct ShakeEffect: GeometryEffect {
    var animatableData: CGFloat

    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 7 * sin(animatableData * .pi * 4), y: 0))
    }
}

// MARK: - Toast

/// A short note at the top of the screen that goes away by itself.
struct Toast: Equatable, Identifiable {
    enum Kind: Equatable { case success, info, warning }
    let id = UUID()
    let text: String
    var kind: Kind = .info
}

struct ToastView: View {
    let toast: Toast

    private var icon: String {
        switch toast.kind {
        case .success: return "checkmark.circle.fill"
        case .info: return "info.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        }
    }

    private var color: Color {
        switch toast.kind {
        case .success: return Palette.green
        case .info: return Palette.primary
        case .warning: return .orange
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 18, weight: .semibold)).foregroundStyle(color)
            Text(toast.text).font(.system(size: 15, weight: .semibold)).multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 18).padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(color.opacity(0.35), lineWidth: 1))
        .shadow(color: .black.opacity(0.18), radius: 16, y: 8)
        .padding(.horizontal, 20)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Formatting

enum Fmt {
    /// "13 июля 2027"; "Бессрочно" for no expiry.
    static func date(_ unix: Int64) -> String {
        guard unix > 0 else { return "Бессрочно" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "d MMMM yyyy"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(unix)))
    }

    /// "только что", "5 минут назад".
    static func ago(_ date: Date, now: Date = Date()) -> String {
        guard now.timeIntervalSince(date) >= 60 else { return "только что" }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.unitsStyle = .full
        return f.localizedString(for: date, relativeTo: now)
    }

    /// 🇫🇮 from "FI".
    static func flag(_ code: String) -> String? {
        let letters = Array(code.uppercased().unicodeScalars)
        guard letters.count == 2, letters.allSatisfy({ (65...90).contains($0.value) }) else { return nil }
        var out = ""
        for s in letters {
            if let r = Unicode.Scalar(0x1F1E6 + s.value - 65) { out.unicodeScalars.append(r) }
        }
        return out
    }

    /// "Финляндия" from "FI".
    static func country(_ code: String) -> String? {
        Locale(identifier: "ru_RU").localizedString(forRegionCode: code.uppercased())
    }
}

// MARK: - Backdrops

/// A backdrop with slowly drifting light in the logo's colours (splash, key entry).
struct AuroraBackground: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { ctx in
            let t = CGFloat(ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3600))
            GeometryReader { g in
                let w = g.size.width
                let h = g.size.height
                ZStack {
                    Palette.background
                    blob(Palette.cyan, size: w * 1.1)
                        .offset(x: w * (0.08 * sin(t / 5) - 0.28), y: h * (0.05 * cos(t / 6) - 0.3))
                    blob(Palette.primary, size: w * 1.25)
                        .offset(x: w * (0.07 * cos(t / 7) + 0.3), y: h * (0.06 * sin(t / 4.5) - 0.04))
                    blob(Palette.violet, size: w * 1.15)
                        .offset(x: w * (0.09 * sin(t / 6.5) - 0.12), y: h * (0.05 * cos(t / 5.5) + 0.34))
                }
                .frame(width: w, height: h)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    private func blob(_ color: Color, size: CGFloat) -> some View {
        let strength = scheme == .dark ? 0.42 : 0.26
        return Circle()
            .fill(RadialGradient(colors: [color.opacity(strength), color.opacity(0)],
                                 center: .center, startRadius: 0, endRadius: size / 2))
            .frame(width: size, height: size)
    }
}

/// The logo with a soft glow behind it that slowly breathes.
struct GlowingLogo: View {
    let size: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { ctx in
            let t = CGFloat(ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3600))
            let breath = reduceMotion ? 0 : sin(t * 1.4)
            ZStack {
                Circle()
                    .fill(RadialGradient(colors: [Palette.primary.opacity(0.5), Palette.violet.opacity(0.22), Color.clear],
                                         center: .center, startRadius: 0, endRadius: size * 0.78))
                    .frame(width: size * 1.6, height: size * 1.6)
                    .scaleEffect(1 + 0.06 * breath)
                    .opacity(0.85 + 0.15 * breath)
                Image("Logo").resizable().scaledToFit().frame(width: size, height: size)
                    .shadow(color: Palette.primary.opacity(0.45), radius: size * 0.14, y: size * 0.05)
            }
        }
        .frame(width: size * 1.6, height: size * 1.6)
        .padding(-size * 0.25)
        .accessibilityHidden(true)
    }
}

/// Two or three tabs in a capsule; the picked one slides under its title (not a stock picker).
struct SegmentedTabs: View {
    struct Item: Identifiable {
        let id: Int
        let title: String
        let icon: String
    }

    let items: [Item]
    @Binding var selection: Int
    @Namespace private var picked

    var body: some View {
        HStack(spacing: 4) {
            ForEach(items) { item in
                let on = item.id == selection
                Button {
                    guard !on else { return }
                    Haptics.selection()
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.82)) { selection = item.id }
                } label: {
                    Label(item.title, systemImage: item.icon)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(on ? Color.white : Color.secondary)
                        .frame(maxWidth: .infinity, minHeight: 42)
                        .background {
                            if on {
                                Capsule()
                                    .fill(Palette.accentGradient)
                                    .shadow(color: Palette.primary.opacity(0.35), radius: 8, y: 3)
                                    .matchedGeometryEffect(id: "tab", in: picked)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Palette.surface, in: Capsule())
        .overlay(Capsule().stroke(Palette.outline.opacity(0.6), lineWidth: 1))
    }
}
