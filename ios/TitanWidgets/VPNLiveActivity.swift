import ActivityKit
import SwiftUI
import WidgetKit

/// While the VPN is on: the server and the time on it, on the Lock Screen and in the Dynamic
/// Island. The app starts and ends it (LiveActivity.swift). No widgetURL: a tap just opens the
/// app (titanvps:// links are for keys).
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
                    ShieldBadge(connected: state.connected, size: 40)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ElapsedTime(state: state, size: 20)
                        .frame(width: 80, alignment: .trailing)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text("Titan VPS")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.white.opacity(0.6))
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 10) {
                        FlagView(flag: state.flag, size: 24)
                        Text(state.server)
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Spacer(minLength: 6)
                        StatusPill(connected: state.connected)
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 6)
                }
            } compactLeading: {
                Image(systemName: state.connected ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(state.connected ? Brand.green : Brand.sky)
            } compactTrailing: {
                ElapsedTime(state: state, size: 14, color: Brand.green)
                    .frame(width: 52, alignment: .trailing)
            } minimal: {
                Image(systemName: state.connected ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(state.connected ? Brand.green : Brand.sky)
            }
            .keylineTint(Brand.green)
        }
    }
}

/// Lock Screen and the banner: the shield, the server, the time on.
private struct ActivityBanner: View {
    let state: VPNActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 14) {
            ShieldBadge(connected: state.connected, size: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text("TITAN VPS")
                    .font(.system(size: 11, weight: .bold))
                    .kerning(1.2)
                    .foregroundStyle(Color.white.opacity(0.5))
                HStack(spacing: 7) {
                    FlagView(flag: state.flag, size: 19)
                    Text(state.server)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                }
                HStack(spacing: 6) {
                    Circle()
                        .fill(state.connected ? Brand.green : Brand.sky)
                        .frame(width: 7, height: 7)
                    Text(state.connected ? "Подключено" : "Подключаемся…")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(state.connected ? Brand.green : Brand.sky)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                ElapsedTime(state: state, size: 24)
                    .frame(width: 96, alignment: .trailing)
                if state.connected {
                    Text("в сети")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Color.white.opacity(0.5))
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
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

/// Green shield when connected, the brand's blue to violet while it connects again.
private struct ShieldBadge: View {
    let connected: Bool
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(connected ? Brand.greenGradient : Brand.accentGradient)
            Image(systemName: connected ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                .font(.system(size: size * 0.46, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .shadow(color: (connected ? Brand.green : Brand.blue).opacity(0.45), radius: 8)
    }
}

/// The time since connecting ("12:34", "1:02:03"), arrows while it connects again.
private struct ElapsedTime: View {
    let state: VPNActivityAttributes.ContentState
    let size: CGFloat
    var color: Color = .white

    var body: some View {
        if state.connected {
            Text(timerInterval: state.connectedSince...Date.distantFuture, countsDown: false)
                .font(.system(size: size, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(color)
                .multilineTextAlignment(.trailing)
                .lineLimit(1)
        } else {
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: size * 0.85, weight: .semibold))
                .foregroundStyle(Brand.sky)
        }
    }
}

private struct FlagView: View {
    let flag: String?
    let size: CGFloat

    var body: some View {
        if let flag {
            Text(flag).font(.system(size: size))
        } else {
            Image(systemName: "globe")
                .font(.system(size: size * 0.8, weight: .semibold))
                .foregroundStyle(Brand.sky)
        }
    }
}

private struct StatusPill: View {
    let connected: Bool

    var body: some View {
        let color = connected ? Brand.green : Brand.sky
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(connected ? "Подключено" : "Подключаемся…")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(color)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(color.opacity(0.16), in: Capsule())
    }
}
