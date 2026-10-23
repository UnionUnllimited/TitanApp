import SwiftUI

/// Выкл | Вкл as one slider: the knob is dragged across by hand (or a side tapped). While a
/// ping runs (it turns the VPN off for its time and back on after) the slider is locked:
/// it darkens, the knob shows a lock and a shine runs over it; a tap then only shakes it.
/// It glows in its state's colour (none while locked): the parent leaves room for it.
struct PowerSlider: View {
    enum Phase: Equatable { case off, connecting, on, disconnecting }

    let phase: Phase
    var locked = false
    var lockedText = "Пинг…"
    /// Failed connect attempts so far: a new one puts a just-moved knob back with a shake
    /// (a failure can come without the phase changing, e.g. .off → .error).
    var failures = 0
    /// Moved by the user: true to turn on. False back means it can't be done now (the knob
    /// returns and shakes).
    let onToggle: (Bool) -> Bool
    var onLockedTap: () -> Void = {}
    /// A sideways drag holds the knob (true) or let it go (false): the page shouldn't scroll
    /// meanwhile, or its pan takes the touch and the drag is lost.
    var onHold: (Bool) -> Void = { _ in }

    private struct AxisLock: Equatable {
        var start: CGPoint
        var horizontal: Bool
    }

    private struct DragState: Equatable {
        var horizontal: Bool
        var dx: CGFloat
    }

    @GestureState(resetTransaction: Transaction(animation: .spring(response: 0.35, dampingFraction: 0.8)))
    private var dragging: DragState? = nil
    /// The side just picked, held until the VPN's state follows (or for a moment at most).
    @State private var pending: Bool?
    /// Each drag's direction, decided on its first move past 16 pt (by where it started): only
    /// a clearly sideways one moves the knob or switches; anything else is the screen scrolling.
    @State private var axis: AxisLock?
    @State private var held = false
    @State private var shakes: CGFloat = 0
    @State private var nudge: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let height: CGFloat = 76
    private let inset: CGFloat = 6
    /// The green gradient's own colour, for the glow while on.
    private static let onGlow = Color(UIColor(hex: 0x16A34A))

    private var isOn: Bool { pending ?? (phase == .on || phase == .connecting) }
    private var busy: Bool { phase == .connecting || phase == .disconnecting }

    var body: some View {
        GeometryReader { geo in
            let knobWidth = max((geo.size.width - inset * 2) / 2, 1)
            let maxX = max(geo.size.width - inset * 2 - knobWidth, 1)
            let x = knobX(maxX: maxX)
            let progress = x / maxX
            ZStack(alignment: .leading) {
                track(progress: progress)
                sideLabels(progress: progress)
                knob(progress: progress)
                    .frame(width: knobWidth, height: height - inset * 2)
                    .offset(x: inset + x + nudge)
            }
            .frame(width: geo.size.width, height: height)
            .overlay {
                if locked { Capsule().fill(Color.black.opacity(0.28)).allowsHitTesting(false) }
            }
            .shimmer(locked, tint: Color.white.opacity(0.45), duration: 1.4)
            .contentShape(Capsule())
            .onTapGesture(coordinateSpace: .local) { point in
                tapped(right: point.x > geo.size.width / 2)
            }
            .simultaneousGesture(drag(maxX: maxX))
            // A tick when the dragged knob passes the middle.
            .onChange(of: x > maxX / 2) { _ in
                if dragging != nil { Haptics.selection() }
            }
        }
        .frame(height: height)
        .background { glow }
        .modifier(ShakeEffect(animatableData: shakes))
        .saturation(locked ? 0.15 : 1)
        .animation(.easeInOut(duration: 0.35), value: locked)
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: isOn)
        .animation(.easeInOut(duration: 0.25), value: phase)
        .onChange(of: phase) { _ in pending = nil }
        .onChange(of: dragging?.horizontal == true) { holding in
            held = holding
            onHold(holding)
        }
        .onDisappear { if held { held = false; onHold(false) } }
        .onChange(of: failures) { _ in
            guard pending != nil else { return }
            withAnimation(.spring(response: 0.38, dampingFraction: 0.78)) { pending = nil }
            refuse()
        }
        .task(id: pending) {
            guard pending != nil else { return }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled { pending = nil }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("VPN")
        .accessibilityValue(locked ? "недоступно, идёт пинг" : isOn ? "включён" : "выключен")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { tapped(right: !isOn) }
    }

    /// Where the knob is: at its side, or under the finger while dragged.
    private func knobX(maxX: CGFloat) -> CGFloat {
        let base: CGFloat = isOn ? maxX : 0
        guard let d = dragging, d.horizontal, !locked else { return base }
        return min(max(base + d.dx, 0), maxX)
    }

    private func track(progress: CGFloat) -> some View {
        Capsule()
            .fill(Palette.track)
            .overlay(Capsule().fill(Palette.green.opacity(0.16 * Double(progress))))
            .shimmer(busy && !locked, tint: Palette.primary, duration: 1.2)
            .overlay(Capsule().stroke(Palette.outline.opacity(0.7), lineWidth: 1))
    }

    /// A soft light around the slider: green while on, blue while it connects (breathing slowly)
    /// or goes off, a faint blue while off; none while locked. A shadow of a capsule under the
    /// track, so only the light outside it shows.
    private var glow: some View {
        let color = phase == .on ? Self.onGlow : Palette.primary
        let strength: Double = locked ? 0 : phase == .on ? 0.45 : busy ? 0.4 : 0.18
        let breathes = busy && !locked && !reduceMotion
        return TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !breathes)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3600)
            // One breath in about 2.5 s, between half and full strength.
            let breath = breathes ? 0.75 + 0.25 * sin(t * 2.5) : 1
            Capsule()
                .fill(Palette.track)
                .shadow(color: color.opacity(strength * breath), radius: 18)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// What the free half of the track says: where the knob would go.
    private func sideLabels(progress: CGFloat) -> some View {
        HStack(spacing: 0) {
            Label("Выкл", systemImage: "power")
                .frame(maxWidth: .infinity)
                .opacity(Double(progress))
            HStack(spacing: 7) {
                Text("Вкл")
                Image(systemName: "chevron.right")
                    .font(.scaled(15, weight: .bold))
            }
            .frame(maxWidth: .infinity)
            .shimmer(phase == .off && !locked, tint: Palette.primary, duration: 2)
            .opacity(Double(1 - progress))
        }
        .font(.scaled(20, weight: .semibold))
        .lineLimit(1)
        .foregroundStyle(.secondary)
        .padding(.horizontal, inset)
        .allowsHitTesting(false)
    }

    private func knob(progress: CGFloat) -> some View {
        let lit = progress > 0.5
        return ZStack {
            Capsule()
                .fill(Palette.knob)
                .shadow(color: .black.opacity(0.18), radius: 6, y: 3)
            Capsule()
                .fill(phase == .connecting ? Palette.accentGradient : Palette.greenGradient)
                .opacity(Double(progress))
                .shadow(color: (phase == .connecting ? Palette.primary : Palette.green).opacity(0.45 * Double(progress)), radius: 10, y: 4)
            HStack(spacing: 10) {
                if locked {
                    Image(systemName: "lock.fill")
                    Text(lockedText)
                } else if busy {
                    ProgressView().tint(lit ? Color.white : Palette.primary)
                    Text(phase == .connecting ? "Подключение" : "Отключение")
                } else if lit {
                    Image(systemName: "checkmark.shield.fill")
                    Text("Вкл")
                } else {
                    Image(systemName: "power")
                    Text("Выкл")
                }
            }
            .font(.scaled(22, weight: .bold))
            .foregroundStyle(lit ? Color.white : Color.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, 12)
        }
        .allowsHitTesting(false)
    }

    /// Sideways only when clearly so (within about 34° of level): looser than a row's, as the
    /// knob is a big sideways target, yet a thumb's up or down stroke stays the screen's.
    private static func sideways(_ t: CGSize) -> Bool { abs(t.width) > abs(t.height) * 1.5 }

    private func drag(maxX: CGFloat) -> some Gesture {
        // In screen points and past the page's own start, as a server row's (SwipeActions).
        DragGesture(minimumDistance: 16, coordinateSpace: .global)
            .updating($dragging) { value, state, transaction in
                guard !locked else { return }
                // Decided once per drag, in screen points: anything but a clearly sideways move
                // is the screen scrolling.
                if state == nil {
                    state = DragState(horizontal: Self.sideways(value.translation), dx: 0)
                }
                if state?.horizontal == true {
                    state?.dx = value.translation.width
                    transaction.animation = .interactiveSpring(response: 0.18, dampingFraction: 0.86)
                }
            }
            .onChanged { value in
                // A new drag (a cancelled one never ended): its direction is decided now.
                if axis?.start != value.startLocation {
                    axis = AxisLock(start: value.startLocation, horizontal: Self.sideways(value.translation))
                }
            }
            .onEnded { value in
                let horizontal = axis?.start == value.startLocation && axis?.horizontal == true
                axis = nil
                // The screen scrolls again at once, whatever comes next.
                if held { held = false; onHold(false) }
                guard horizontal else { return }
                if locked { refuse(); onLockedTap(); return }
                let wasOn = isOn
                let base: CGFloat = wasOn ? maxX : 0
                // Where it was let go, and where a flick would have thrown it.
                let end = base + value.translation.width
                let thrown = base + value.predictedEndTranslation.width
                let target = (end + thrown) / 2 > maxX / 2
                if target != wasOn { commit(target) }
            }
    }

    private func tapped(right: Bool) {
        if locked { refuse(); onLockedTap(); return }
        if right == isOn { hint(); return }
        commit(right)
    }

    private func commit(_ on: Bool) {
        if onToggle(on) {
            Haptics.tap(.medium)
            withAnimation(.spring(response: 0.38, dampingFraction: 0.78)) { pending = on }
        } else {
            refuse()
        }
    }

    private func refuse() {
        Haptics.error()
        withAnimation(.linear(duration: 0.4)) { shakes += 1 }
    }

    /// A tap on the knob itself: it leans toward the other side, to show it can be dragged.
    private func hint() {
        Haptics.tap(.soft)
        let toward: CGFloat = isOn ? -18 : 18
        withAnimation(.spring(response: 0.2, dampingFraction: 0.5)) { nudge = toward }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 180_000_000)
            withAnimation(.spring(response: 0.35, dampingFraction: 0.55)) { nudge = 0 }
        }
    }
}
