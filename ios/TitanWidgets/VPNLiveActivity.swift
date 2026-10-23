import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// The VPN on or off, on the Lock Screen and in the Dynamic Island (the app starts, updates and
/// ends it: LiveActivity.swift): the server with its flag, ‹ › for the previous and the next one
/// (SwitchServerIntent, run in the app) and ⏻. On: «Подключено» with the timer, the speed, the
/// ping and the session's traffic as Главная shows them, until they go stale (the app isn't open
/// to send new ones). The island shows it only while the app isn't open: its speed is the one
/// sent on leaving the app, for 15 s (a live one in the background would need server pushes).
/// Off: «Выключено» with the server ⏻ starts; ‹ › only move the pick. No widgetURL: a tap
/// elsewhere just opens the app (titanvps:// links are for keys).
struct VPNLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VPNActivityAttributes.self) { context in
            ActivityBanner(state: context.state, stale: context.isStale)
                .activityBackgroundTint(Brand.navy)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
            let numbers = Numbers(state: state, stale: context.isStale)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ServerCircle(state: state, size: 44)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 4) {
                        // With the numbers under it, on one line: a second would push ‹ ⏻ › past
                        // the island's height (up to 160 pt; the bottom region is under the rest).
                        Text(state.server)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(numbers.isEmpty ? 2 : 1)
                            .minimumScaleFactor(numbers.isEmpty ? 0.8 : 0.7)
                        StatusLine(state: state, stale: context.isStale, size: 12)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 2)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 6) {
                        if !numbers.isEmpty { NumbersRow(numbers: numbers) }
                        HStack(spacing: 10) {
                            SwitchButton(forward: false, titled: true)
                            PowerButton(off: state.shown == .off)
                            SwitchButton(forward: true, titled: true)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 6)
                }
            } compactLeading: {
                ServerCircle(state: state, size: 20)
            } compactTrailing: {
                CompactTrailing(numbers: numbers)
            } minimal: {
                ServerCircle(state: state, size: 20)
            }
            .keylineTint(StatusLook(state, stale: context.isStale).color)
        }
    }
}

/// Lock Screen and the banner: the flag, the whole name, the state with the numbers, ‹ › (and
/// ⏻ while off).
private struct ActivityBanner: View {
    let state: VPNActivityAttributes.ContentState
    let stale: Bool

    var body: some View {
        let numbers = Numbers(state: state, stale: stale)
        HStack(spacing: 12) {
            ServerCircle(state: state, size: 46)
            VStack(alignment: .leading, spacing: 3) {
                Text("TITAN VPS")
                    .font(.system(size: 11, weight: .bold))
                    .kerning(1.2)
                    .foregroundStyle(Color.white.opacity(0.5))
                // Whole: on two lines, a little smaller if need be, rather than cut with "…"
                // (a Live Activity can't scroll it).
                Text(state.server)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                StatusLine(state: state, stale: stale)
                if !numbers.isEmpty { NumbersLines(numbers: numbers).padding(.top, 1) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 8) {
                SwitchButton(forward: false)
                if state.shown == .off { PowerButton(off: true, round: true) }
                SwitchButton(forward: true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 14)
        .background {
            // Navy with the logo's blue and violet glowing from the corners.
            ZStack {
                Brand.navy
                RadialGradient(colors: [Brand.blue.opacity(0.38), .clear], center: .topLeading, startRadius: 0, endRadius: 190)
                RadialGradient(colors: [Brand.violet.opacity(0.3), .clear], center: .bottomTrailing, startRadius: 0, endRadius: 210)
            }
        }
    }
}

/// The server's flag filled into a circle (FlagCircle in ios/Shared), as in the app; АВТО's
/// bolt or a server without one. The name beside it keeps its own ⚡ or 🚀.
private struct ServerCircle: View {
    let state: VPNActivityAttributes.ContentState
    let size: CGFloat

    var body: some View {
        FlagCircle(flag: state.flag, name: state.server, size: size, fill: Color.white.opacity(0.1),
                   stroke: Color.white.opacity(0.15), bolt: .yellow, symbol: Brand.sky)
    }
}

private extension VPNActivityAttributes.ContentState.Phase {
    var title: String {
        switch self {
        case .on: return "Подключено"
        case .switching: return "Переключаемся…"
        case .connecting: return "Подключаемся…"
        case .off: return "Выключено"
        }
    }

    /// Green up, the brand's blue on the way, the app's red off.
    var color: Color {
        switch self {
        case .on: return Brand.green
        case .switching, .connecting: return Brand.sky
        case .off: return Brand.red
        }
    }
}

/// What the activity says: its phase, or, for a "Подключаемся…" / "Переключаемся…" that went
/// stale (an intent's, with no update for a minute: the app was suspended before the VPN
/// settled), that it doesn't know, in grey, rather than a connect that never ends.
private struct StatusLook {
    let phase: VPNActivityAttributes.ContentState.Phase
    let unknown: Bool

    init(_ state: VPNActivityAttributes.ContentState, stale: Bool) {
        phase = state.shown
        unknown = stale && (phase == .connecting || phase == .switching)
    }

    var title: String { unknown ? "Состояние неизвестно" : phase.title }
    var color: Color { unknown ? Color.white.opacity(0.55) : phase.color }
}

/// The state in its colour; up, with the time since it came up (the system counts it: no
/// updates needed).
private struct StatusLine: View {
    let state: VPNActivityAttributes.ContentState
    let stale: Bool
    var size: CGFloat = 13

    var body: some View {
        let status = StatusLook(state, stale: stale)
        HStack(spacing: 6) {
            Circle().fill(status.color).frame(width: 7, height: 7)
            Text(status.title)
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(status.color)
            if status.phase == .on, let since = state.since {
                Text(timerInterval: since...Date.distantFuture, countsDown: false)
                    .font(.system(size: size, weight: .medium).monospacedDigit())
                    .foregroundStyle(Color.white.opacity(0.6))
                    .multilineTextAlignment(.leading)
            }
        }
        .lineLimit(1)
        .accessibilityElement(children: .combine)
    }
}

/// The numbers while connected, as Главная shows them: ↓ ↑ speed (its speed line), the ping in
/// its colours, what this session used. Stale (no update for 15 s: the app isn't open to send
/// one): no speed, the ping and the traffic under the time they were read.
private struct Numbers {
    let state: VPNActivityAttributes.ContentState
    let stale: Bool

    private var on: Bool { state.shown == .on }
    var down: Double? { on && !stale ? state.down : nil }
    var up: Double? { on && !stale ? state.up : nil }
    var ping: Int? { on ? state.ping : nil }
    var traffic: Int64? { on ? state.traffic.flatMap { $0 > 0 ? $0 : nil } : nil }

    var speedText: String? {
        guard let down, let up else { return nil }
        return "↓ \(TrafficText.speed(down))    ↑ \(TrafficText.speed(up))"
    }

    var trafficText: String? { traffic.map { "Потрачено " + TrafficText.bytes($0) } }

    /// «Обновлено в 14:05» once stale.
    var asOf: String? {
        guard on, stale, let at = state.statsAt else { return nil }
        return "Обновлено в " + Clock.time(at)
    }

    var isEmpty: Bool { speedText == nil && ping == nil && traffic == nil }

    /// «Загрузка 3.2 МБ/с, отдача 140 КБ/с, пинг 45 мс, потрачено 120 МБ».
    var accessibility: String {
        var parts: [String] = []
        if let down, let up { parts.append("загрузка \(TrafficText.speed(down)), отдача \(TrafficText.speed(up))") }
        if let ping { parts.append("пинг \(ping) мс") }
        if let traffic { parts.append("потрачено " + TrafficText.bytes(traffic)) }
        if let asOf { parts.append(asOf.lowercased()) }
        let text = parts.joined(separator: ", ")
        return text.prefix(1).uppercased() + text.dropFirst()
    }
}

/// «14:05».
private enum Clock {
    private static let format: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "HH:mm"
        return f
    }()

    static func time(_ date: Date) -> String { format.string(from: date) }
}

/// The Lock Screen's two lines: the speed (or when a stale one was read), then the ping and the
/// traffic. Fixed sizes as the rest of the view, one line each, a little smaller if need be:
/// the system's text size doesn't push them out of the activity's height.
private struct NumbersLines: View {
    let numbers: Numbers

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let speed = numbers.speedText {
                Text(speed)
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(Color.white.opacity(0.75))
            } else if let asOf = numbers.asOf {
                Text(asOf)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.5))
            }
            if numbers.ping != nil || numbers.trafficText != nil {
                HStack(spacing: 8) {
                    if let ping = numbers.ping { PingLabel(ms: ping) }
                    if let traffic = numbers.trafficText {
                        Text(traffic)
                            .font(.system(size: 12, weight: .medium).monospacedDigit())
                            .foregroundStyle(Color.white.opacity(0.75))
                    }
                }
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(numbers.accessibility)
    }
}

/// The expanded island's, centred and always on one line: a second would push ‹ ⏻ › past the
/// island's height (up to 160 pt). Where the whole doesn't fit (MB/s both ways and «Потрачено …»
/// on a narrow island), the traffic after an ↑↓ icon without the word; then a little smaller.
private struct NumbersRow: View {
    let numbers: Numbers

    var body: some View {
        ViewThatFits(in: .horizontal) {
            row(spent: true)
            row(spent: false)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.75)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(numbers.accessibility)
    }

    private func row(spent: Bool) -> some View {
        HStack(spacing: spent ? 12 : 10) {
            if let speed = numbers.speedText {
                Text(speed)
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(Color.white.opacity(0.75))
            } else if let asOf = numbers.asOf {
                Text(asOf)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.5))
            }
            if let ping = numbers.ping { PingLabel(ms: ping) }
            if let traffic = numbers.traffic {
                HStack(spacing: 3) {
                    if spent {
                        Text("Потрачено " + TrafficText.bytes(traffic))
                    } else {
                        Image(systemName: "arrow.up.arrow.down").font(.system(size: 10, weight: .semibold))
                        Text(TrafficText.bytes(traffic))
                    }
                }
                .font(.system(size: 12, weight: .medium).monospacedDigit())
                .foregroundStyle(Color.white.opacity(0.75))
            }
        }
    }
}

/// The ping in Главная's colours (ServerRow.color): green under 500 ms, orange under 1.5 s,
/// else red.
private struct PingLabel: View {
    let ms: Int

    var body: some View {
        let color = Self.color(ms)
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text("\(ms) мс")
                .font(.system(size: 12, weight: .semibold).monospacedDigit())
                .foregroundStyle(color)
        }
    }

    static func color(_ ms: Int) -> Color {
        if ms < 0 { return Brand.red }
        if ms < 500 { return Brand.green }
        if ms < 1500 { return .orange }
        return Brand.red
    }
}

/// The compact island's right: ↓ the download speed while there is traffic, else the ping, else
/// the state's dot. Stale: the dot (no room to say since when).
private struct CompactTrailing: View {
    let numbers: Numbers

    var body: some View {
        if let down = numbers.down, down >= 10 * 1024 {
            Text("↓" + TrafficText.shortSpeed(down))
                .font(.system(size: 12, weight: .semibold).monospacedDigit())
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .accessibilityLabel("Загрузка " + TrafficText.speed(down))
        } else if let ping = numbers.ping, !numbers.stale {
            Text("\(ping) мс")
                .font(.system(size: 12, weight: .semibold).monospacedDigit())
                .foregroundStyle(PingLabel.color(ping))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .accessibilityLabel("Пинг \(ping) мс")
        } else {
            StatusDot(status: StatusLook(numbers.state, stale: numbers.stale))
        }
    }
}

/// The compact island's state: a dot in the state's colour.
private struct StatusDot: View {
    let status: StatusLook

    var body: some View {
        Circle()
            .fill(status.color)
            .frame(width: 8, height: 8)
            .padding(3)
            .background(Circle().fill(status.color.opacity(0.22)))
            .accessibilityLabel(status.title)
    }
}

/// ‹ or ›: the previous or the next server, as listed in the app (SwitchServerIntent switches
/// in the app's process; while off it only moves the pick). Round on the Lock Screen; titled
/// capsules in the Dynamic Island.
private struct SwitchButton: View {
    let forward: Bool
    var titled = false

    var body: some View {
        Button(intent: SwitchServerIntent(forward: forward)) {
            Group {
                if titled {
                    HStack(spacing: 5) {
                        if !forward { Image(systemName: "chevron.left") }
                        Text(forward ? "Следующий" : "Предыдущий").lineLimit(1).minimumScaleFactor(0.8)
                        if forward { Image(systemName: "chevron.right") }
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .background(Color.white.opacity(0.14), in: Capsule())
                } else {
                    Image(systemName: forward ? "chevron.right" : "chevron.left")
                        .font(.system(size: 15, weight: .semibold))
                        .frame(width: 38, height: 38)
                        .background(Color.white.opacity(0.14), in: Circle())
                }
            }
            .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(forward ? "Следующий сервер" : "Предыдущий сервер")
    }
}

/// ⏻ (StopVPNIntent / StartVPNIntent, run in the app): the VPN off, in red; while it is off,
/// on, in green. Between ‹ and › in the expanded island; round on the Lock Screen, where it
/// shows only while off.
private struct PowerButton: View {
    let off: Bool
    var round = false

    var body: some View {
        Group {
            if off {
                Button(intent: StartVPNIntent()) { face }
            } else {
                Button(intent: StopVPNIntent()) { face }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(off ? "Включить VPN" : "Выключить VPN")
    }

    private var face: some View {
        Image(systemName: "power")
            .font(.system(size: round ? 15 : 16, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: round ? 38 : 44, height: round ? 38 : 36)
            .background((off ? Brand.green : Brand.red).opacity(0.8), in: Capsule())
    }
}
