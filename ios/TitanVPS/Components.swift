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

enum VoiceOver {
    /// VoiceOver says [text] (a moment later, so the tap's own speech doesn't cut it off): a
    /// note that shows up away from the focus is otherwise not heard.
    static func announce(_ text: String) {
        guard UIAccessibility.isVoiceOverRunning else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            UIAccessibility.post(notification: .announcement, argument: text)
        }
    }
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
            .font(.scaled(17, weight: .semibold))
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
            .font(.scaled(16, weight: .semibold))
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity, minHeight: height)
            .padding(.horizontal, 12)
            .background(tint.opacity(configuration.isPressed ? 0.2 : 0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .opacity(isEnabled ? 1 : 0.5)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// A button that acts on a tap only: one in a swipeable row isn't pressed by the swipe.
struct TapOnlyButtonStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .contentShape(Rectangle())
            .onTapGesture { configuration.trigger() }
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
                .font(.scaled(size * 0.4, weight: .semibold))
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
                            .font(.scaled(10, weight: .heavy))
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
        // VoiceOver: an ordinary switch with the label's text.
        .accessibilityRepresentation {
            Toggle(isOn: configuration.$isOn) { configuration.label }
                .toggleStyle(.switch)
        }
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
            .font(.scaled(size * 0.47, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(
                LinearGradient(colors: [color.opacity(0.8), color], startPoint: .top, endPoint: .bottom),
                in: RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
            )
            .accessibilityHidden(true)
    }
}

extension FlagCircle {
    /// A server's round flag (ios/Shared) in the app's colours, from its whole name.
    init(server name: String, size: CGFloat) {
        let parts = name.splitFlag
        self.init(flag: parts.0, name: parts.1, size: size, fill: Palette.track, stroke: Palette.outline, bolt: Palette.primary)
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
                Text(title).font(.scaled(16, weight: .medium)).foregroundStyle(titleColor)
                if let subtitle {
                    Text(subtitle).font(.scaled(12)).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            trailing
            if chevron {
                Image(systemName: "chevron.right").font(.scaled(13, weight: .semibold)).foregroundStyle(.tertiary)
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
            .font(.scaled(13, weight: .semibold))
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
            Image(systemName: icon).font(.scaled(18, weight: .semibold)).foregroundStyle(color)
            Text(toast.text).font(.scaled(15, weight: .semibold)).multilineTextAlignment(.leading)
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

extension Font {
    /// The system font at [size] grown with the phone's text size (Dynamic Type): text up to
    /// 1.25×, titles and big numbers (24 pt and up) not at all, so that the design holds.
    /// RootView rebuilds the screens when the text size changes.
    static func scaled(_ size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> Font {
        let limit: CGFloat = size >= 24 ? 1 : 1.25
        return .system(size: min(UIFontMetrics.default.scaledValue(for: size), size * limit), weight: weight, design: design)
    }
}

enum Fmt {
    /// "2.4 МБ/с" as number and unit (the number drawn larger).
    static func speedParts(_ bytesPerSecond: Double) -> (number: String, unit: String) {
        let b = max(bytesPerSecond, 0)
        if b >= 1_048_576 { return (String(format: "%.1f", b / 1_048_576), "МБ/с") }
        if b >= 1024 { return (String(format: "%.0f", b / 1024), "КБ/с") }
        return (String(format: "%.0f", b), "Б/с")
    }

    static func speed(_ bytesPerSecond: Double) -> String {
        let p = speedParts(bytesPerSecond)
        return p.number + " " + p.unit
    }

    static func bytes(_ count: UInt64) -> String {
        formatBytes(Int64(clamping: count))
    }

    // Made once: the screens call these on every redraw (on the main thread).
    private static let dayFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "d MMMM yyyy"
        return f
    }()

    private static let relative: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.unitsStyle = .full
        return f
    }()

    /// "13 июля 2027"; "Бессрочно" for no expiry.
    static func date(_ unix: Int64) -> String {
        guard unix > 0 else { return "Бессрочно" }
        return dayFormat.string(from: Date(timeIntervalSince1970: TimeInterval(unix)))
    }

    /// "только что", "5 минут назад".
    static func ago(_ date: Date, now: Date = Date()) -> String {
        guard now.timeIntervalSince(date) >= 60 else { return "только что" }
        return relative.localizedString(for: date, relativeTo: now)
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
                        .font(.scaled(15, weight: .semibold))
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

// MARK: - Swipe actions

/// What a row does when swiped far enough one way.
struct SwipeAction {
    let icon: String
    let title: String
    let color: Color
    let perform: () -> Void
}

/// A row that can be swiped: right for [right] (shown on the left), left for [left] (shown on
/// the right). Let go past the mark and it happens; the row slides back. In a ScrollView:
/// only a clearly sideways move holds the row; anything else is the page scrolling.
struct SwipeActions<Content: View>: View {
    var right: SwipeAction?
    var left: SwipeAction?
    /// A sideways drag holds the row: the page shouldn't scroll meanwhile.
    var onHold: (Bool) -> Void = { _ in }
    @ViewBuilder var content: Content

    private struct DragState: Equatable {
        var horizontal: Bool
        var dx: CGFloat
    }

    private struct AxisLock: Equatable {
        var start: CGPoint
        var horizontal: Bool
    }

    @GestureState(resetTransaction: Transaction(animation: .spring(response: 0.32, dampingFraction: 0.8)))
    private var dragging: DragState? = nil
    @State private var axis: AxisLock?
    @State private var held = false
    private let mark: CGFloat = 84

    private var offset: CGFloat {
        guard let d = dragging, d.horizontal else { return 0 }
        let dx = d.dx
        if dx > 0 && right == nil || dx < 0 && left == nil { return dx * 0.15 }
        let far = abs(dx)
        // Past the mark it follows the finger reluctantly.
        let shown = far <= mark ? far : mark + (far - mark) * 0.35
        return dx > 0 ? shown : -shown
    }

    var body: some View {
        let dx = offset
        content
            .offset(x: dx)
            .background(alignment: dx >= 0 ? .leading : .trailing) {
                if let action = dx > 0 ? right : (dx < 0 ? left : nil) {
                    revealed(action, width: abs(dx), armed: abs(dx) >= mark, onLeft: dx > 0)
                }
            }
            .simultaneousGesture(gesture)
            .onChange(of: abs(dx) >= mark) { armed in
                if armed, dragging != nil { Haptics.tap(.medium) }
            }
            .onChange(of: dragging?.horizontal == true) { holding in
                held = holding
                onHold(holding)
            }
            .onDisappear { if held { held = false; onHold(false) } }
            .accessibilityActions {
                if let right { Button(right.title, action: right.perform) }
                if let left { Button(left.title, action: left.perform) }
            }
    }

    private func revealed(_ action: SwipeAction, width: CGFloat, armed: Bool, onLeft: Bool) -> some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(action.color.opacity(armed ? 1 : 0.55))
            .frame(width: width + 24)
            .overlay(alignment: onLeft ? .leading : .trailing) {
                VStack(spacing: 3) {
                    Image(systemName: action.icon).font(.scaled(18, weight: .bold))
                        .scaleEffect(armed ? 1.15 : 0.9)
                    Text(action.title).font(.scaled(10, weight: .semibold)).lineLimit(1)
                }
                .foregroundStyle(.white)
                .frame(width: 76)
                .opacity(min(Double(width / mark), 1))
            }
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: armed)
    }

    /// Sideways only when clearly so (within about 27° of level): a thumb's up or down stroke
    /// drifts sideways too, and it must stay the page's.
    private static func sideways(_ t: CGSize) -> Bool { abs(t.width) > abs(t.height) * 2 }

    private var gesture: some Gesture {
        // In screen points: the row moves with the page as it scrolls, so in its own space an
        // up or down drag reads as sideways. Past 20 pt, after the page's own start: on iOS 26
        // (where this no longer runs alongside the page pan) an up or down drag is the page's.
        DragGesture(minimumDistance: 20, coordinateSpace: .global)
            .updating($dragging) { value, state, transaction in
                if state == nil {
                    state = DragState(horizontal: Self.sideways(value.translation), dx: 0)
                }
                if state?.horizontal == true {
                    state?.dx = value.translation.width
                    transaction.animation = .interactiveSpring(response: 0.18, dampingFraction: 0.86)
                }
            }
            .onChanged { value in
                if axis?.start != value.startLocation {
                    axis = AxisLock(start: value.startLocation, horizontal: Self.sideways(value.translation))
                }
            }
            .onEnded { value in
                let horizontal = axis?.start == value.startLocation && axis?.horizontal == true
                axis = nil
                // The page scrolls again at once, whatever comes next.
                if held { held = false; onHold(false) }
                guard horizontal else { return }
                let dx = value.translation.width
                if dx >= mark, let right { Haptics.success(); right.perform() }
                else if dx <= -mark, let left { Haptics.success(); left.perform() }
            }
    }
}
