import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// While the VPN is on: the server with its flag, and ‹ › for the previous and the next one
/// (SwitchServerIntent, run in the app), on the Lock Screen and in the Dynamic Island. The app
/// starts and ends it (LiveActivity.swift). No widgetURL: a tap elsewhere just opens the app
/// (titanvps:// links are for keys).
struct VPNLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VPNActivityAttributes.self) { context in
            ActivityBanner(state: context.state)
                .activityBackgroundTint(Brand.navy)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ServerCircle(state: state, size: 44)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(state.server)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(2)
                            .minimumScaleFactor(0.8)
                        StatusLine(connected: state.connected, size: 12)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 2)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 10) {
                        SwitchButton(forward: false, titled: true)
                        StopButton()
                        SwitchButton(forward: true, titled: true)
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 6)
                }
            } compactLeading: {
                ServerCircle(state: state, size: 20)
            } compactTrailing: {
                StatusDot(connected: state.connected)
            } minimal: {
                ServerCircle(state: state, size: 20)
            }
            .keylineTint(Brand.green)
        }
    }
}

/// Lock Screen and the banner: the flag, the whole name, the state, ‹ ›.
private struct ActivityBanner: View {
    let state: VPNActivityAttributes.ContentState

    var body: some View {
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
                StatusLine(connected: state.connected)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 8) {
                SwitchButton(forward: false)
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

/// Green "Подключено"; the brand's blue while it connects again (another server, a ping).
private struct StatusLine: View {
    let connected: Bool
    var size: CGFloat = 13

    var body: some View {
        let color = connected ? Brand.green : Brand.sky
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(connected ? "Подключено" : "Переключаемся…")
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(color)
                .lineLimit(1)
        }
    }
}

/// The compact island's state: a dot in the same colours.
private struct StatusDot: View {
    let connected: Bool

    var body: some View {
        let color = connected ? Brand.green : Brand.sky
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
            .padding(3)
            .background(Circle().fill(color.opacity(0.22)))
            .accessibilityLabel(connected ? "Подключено" : "Переключаемся")
    }
}

/// ‹ or ›: the previous or the next server, as listed in the app (SwitchServerIntent switches
/// in the app's process). Round on the Lock Screen; titled capsules in the Dynamic Island.
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

/// The expanded island's ⏻ between ‹ and ›: the VPN off (StopVPNIntent, run in the app).
private struct StopButton: View {
    var body: some View {
        Button(intent: StopVPNIntent()) {
            Image(systemName: "power")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 36)
                .background(Color.red.opacity(0.75), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Выключить VPN")
    }
}
