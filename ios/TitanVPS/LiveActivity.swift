import ActivityKit
import Foundation

/// The Live Activity while the VPN is on (TitanWidgets draws it, iOS 18+): it starts when the
/// VPN connects, shows "Подключаемся…" while it connects again (another server, a ping's
/// pause) and ends when the VPN is off. Each VPN change also has Пункт управления read its
/// toggle again. Called from RootView.
@MainActor
enum LiveActivityController {
    /// Bumped on each change: work still waiting from an older one gives way.
    private static var generation = 0

    /// paused: off for a ping, back on in a moment.
    static func vpnChanged(_ state: VPNController.State, server: String?, paused: Bool) {
        VPNControl.reload()
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        let gen = generation
        Task { await apply(state, server: server, paused: paused, gen: gen) }
    }

    /// At launch: an activity left from a VPN that went off while the app wasn't running.
    static func endIfOff() {
        guard #available(iOS 18.0, *) else { return }
        let gen = generation
        let stale = Activity<VPNActivityAttributes>.activities
        guard !stale.isEmpty else { return }
        Task {
            guard !(await TunnelSwitch.isOn()), gen == generation else { return }
            for activity in stale { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    /// Turned off without the app's screen (Siri in the background).
    static func endAll() async {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        for activity in Activity<VPNActivityAttributes>.activities { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    @available(iOS 18.0, *)
    private static func apply(_ state: VPNController.State, server: String?, paused: Bool, gen: Int) async {
        switch state {
        case .connected(let since):
            // The server the tunnel runs: it may have been started from Пункт управления.
            let name = await TunnelSwitch.serverName() ?? server ?? "Titan VPS"
            guard gen == generation else { return }
            let (flag, rest) = name.splitFlag
            let content = ActivityContent(state: VPNActivityAttributes.ContentState(
                server: rest, flag: flag, connectedSince: since, connected: true), staleDate: nil)
            let activities = Activity<VPNActivityAttributes>.activities
            if let activity = activities.first {
                await activity.update(content)
                for extra in activities.dropFirst() { await extra.end(nil, dismissalPolicy: .immediate) }
            } else if ActivityAuthorizationInfo().areActivitiesEnabled {
                _ = try? Activity<VPNActivityAttributes>.request(attributes: VPNActivityAttributes(), content: content, pushType: nil)
            }
        case .connecting, .disconnecting where paused, .off where paused:
            await showReconnecting()
        case .disconnecting, .off, .error:
            // Another server: off for a moment, then on again; the activity stays through it.
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard gen == generation else { return }
            for activity in Activity<VPNActivityAttributes>.activities { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }

    @available(iOS 18.0, *)
    private static func showReconnecting() async {
        guard let activity = Activity<VPNActivityAttributes>.activities.first else { return }
        var state = activity.content.state
        guard state.connected else { return }
        state.connected = false
        await activity.update(ActivityContent(state: state, staleDate: nil))
    }
}
