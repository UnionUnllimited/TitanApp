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
            let label = ServerLabel(state)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ServerTile(icon: label.icon, size: 46)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(label.name)
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
                        SwitchButton(forward: true, titled: true)
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 6)
                }
            } compactLeading: {
                ServerIcon(icon: label.icon, size: 16)
            } compactTrailing: {
                StatusDot(connected: state.connected)
            } minimal: {
                ServerIcon(icon: label.icon, size: 15)
            }
            .keylineTint(Brand.green)
        }
    }
}

/// Lock Screen and the banner: the flag, the whole name, the state, ‹ ›.
private struct ActivityBanner: View {
    let state: VPNActivityAttributes.ContentState

    var body: some View {
        let label = ServerLabel(state)
        HStack(spacing: 12) {
            ServerTile(icon: label.icon, size: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text("TITAN VPS")
                    .font(.system(size: 11, weight: .bold))
                    .kerning(1.2)
                    .foregroundStyle(Color.white.opacity(0.5))
                // Whole: on two lines, a little smaller if need be, rather than cut with "…"
                // (a Live Activity can't scroll it).
                Text(label.name)
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

/// The tile's emoji and the name beside it: the flag, else an emoji the name starts with
/// ("⚡ АВТО"), which the name then goes without.
private struct ServerLabel {
    let icon: String?
    let name: String

    init(_ state: VPNActivityAttributes.ContentState) {
        let rest = String(state.server.dropFirst()).trimmingCharacters(in: .whitespaces)
        if let flag = state.flag {
            icon = flag
            name = state.server
        } else if let first = state.server.first, Self.isEmoji(first), !rest.isEmpty {
            icon = String(first)
            name = rest
        } else {
            icon = nil
            name = state.server
        }
    }

    /// A picture emoji (⚡, 🌍), not a letter or a digit.
    private static func isEmoji(_ c: Character) -> Bool {
        guard let scalar = c.unicodeScalars.first else { return false }
        return scalar.properties.isEmojiPresentation || (scalar.properties.isEmoji && c.unicodeScalars.count > 1)
    }
}

/// The flag (or the name's own emoji); a globe without one.
private struct ServerIcon: View {
    let icon: String?
    let size: CGFloat

    var body: some View {
        if let icon {
            Text(icon).font(.system(size: size))
        } else {
            Image(systemName: "globe")
                .font(.system(size: size * 0.85, weight: .semibold))
                .foregroundStyle(Brand.sky)
        }
    }
}

/// The flag on a soft square.
private struct ServerTile: View {
    let icon: String?
    let size: CGFloat

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
        ServerIcon(icon: icon, size: size * 0.56)
            .frame(width: size, height: size)
            .background(Color.white.opacity(0.1), in: shape)
            .overlay(shape.strokeBorder(Color.white.opacity(0.08), lineWidth: 1))
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
                        Text(forward ? "Следующий" : "Предыдущий").lineLimit(1)
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
