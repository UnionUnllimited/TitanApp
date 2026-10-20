import SwiftUI

/// Выкл | Вкл as one slider: the knob is dragged across by hand (or a side tapped). While a
/// ping runs (it turns the VPN off for its time and back on after) the slider is locked:
/// it darkens, the knob shows a lock and a shine runs over it; a tap then only shakes it.
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
    /// Each drag's direction, decided on its first move (by where it started): only a
    /// sideways one moves the knob or switches; an up or down one is the screen scrolling.
    @State private var axis: AxisLock?
    @State private var shakes: CGFloat = 0
    @State private var nudge: CGFloat = 0

    private let height: CGFloat = 68
    private let inset: CGFloat = 5

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
        .modifier(ShakeEffect(animatableData: shakes))
        .saturation(locked ? 0.15 : 1)
        .animation(.easeInOut(duration: 0.35), value: locked)
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: isOn)
        .animation(.easeInOut(duration: 0.25), value: phase)
        .onChange(of: phase) { _ in pending = nil }
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

    /// What the free half of the track says: where the knob would go.
    private func sideLabels(progress: CGFloat) -> some View {
        HStack(spacing: 0) {
            Label("Выкл", systemImage: "power")
                .frame(maxWidth: .infinity)
                .opacity(Double(progress))
            HStack(spacing: 6) {
                Text("Вкл")
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .bold))
            }
            .frame(maxWidth: .infinity)
            .shimmer(phase == .off && !locked, tint: Palette.primary, duration: 2)
            .opacity(Double(1 - progress))
        }
        .font(.system(size: 17, weight: .semibold))
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
            HStack(spacing: 8) {
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
            .font(.system(size: 17, weight: .bold))
            .foregroundStyle(lit ? Color.white : Color.primary)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .padding(.horizontal, 10)
        }
        .allowsHitTesting(false)
    }

    private func drag(maxX: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .updating($dragging) { value, state, transaction in
                guard !locked else { return }
                // Decided once per drag: a vertical one is the screen scrolling.
                if state == nil {
                    state = DragState(horizontal: abs(value.translation.width) > abs(value.translation.height), dx: 0)
                }
                if state?.horizontal == true {
                    state?.dx = value.translation.width
                    transaction.animation = .interactiveSpring(response: 0.18, dampingFraction: 0.86)
                }
            }
            .onChanged { value in
                // A new drag (a cancelled one never ended): its direction is decided now.
                if axis?.start != value.startLocation {
                    axis = AxisLock(start: value.startLocation,
                                    horizontal: abs(value.translation.width) > abs(value.translation.height))
                }
            }
            .onEnded { value in
                let horizontal = axis?.start == value.startLocation && axis?.horizontal == true
                axis = nil
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
