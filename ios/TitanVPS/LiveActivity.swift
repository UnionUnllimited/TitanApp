import ActivityKit
import Foundation

/// The Live Activity while the VPN is on (TitanWidgets draws it, iOS 18+): it starts when the
/// VPN connects, shows "Подключаемся…" while it connects again (another server, a ping's
/// pause) and ends when the VPN is off. Each VPN change also has Пункт управления read its
/// toggle again. Called from RootView and from the intents (Пункт управления, Siri), which may
/// start one while the app is in the background.
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

    /// At launch and each time the app is opened: the activity and the toggle follow the VPN
    /// again. It may have changed meanwhile, the app can't start an activity in the background
    /// (connected after leaving the app), and the system ends one after 8 h.
    static func sync(_ state: VPNController.State, server: String?, paused: Bool) {
        switch state {
        case .connected: vpnChanged(state, server: server, paused: paused)
        case .off where !paused, .error:
            VPNControl.reload()
            endIfOff()
        default: VPNControl.reload()
        }
    }

    /// An activity left from a VPN that went off while the app wasn't running.
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

    /// Turned on without the app's screen (Пункт управления, Siri): their intent may start one
    /// even with the app in the background.
    static func turnedOn() async {
        guard #available(iOS 18.0, *) else { return }
        guard let since = await TunnelSwitch.connectedSince() else { return }
        generation += 1
        await apply(.connected(since: since), server: nil, paused: false, gen: generation)
    }

    /// Turned off without the app's screen (Пункт управления, Siri).
    static func endAll() async {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        for activity in Activity<VPNActivityAttributes>.activities { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    @available(iOS 18.0, *)
    private static func apply(_ state: VPNController.State, server: String?, paused: Bool, gen: Int) async {
        switch state {
        case .connected(let since):
            // The server the tunnel runs: it may have been started from iOS Settings.
            let name = await TunnelSwitch.serverName() ?? server ?? "Titan VPS"
            guard gen == generation else { return }
            let (flag, rest) = name.splitFlag
            let content = ActivityContent(state: VPNActivityAttributes.ContentState(
                server: rest, flag: flag, connectedSince: since, connected: true), staleDate: nil)
            let all = Activity<VPNActivityAttributes>.activities
            let live = all.filter { $0.isLive }
            if let activity = live.first {
                await activity.update(content)
                for extra in live.dropFirst() { await extra.end(nil, dismissalPolicy: .immediate) }
            } else if ActivityAuthorizationInfo().areActivitiesEnabled {
                _ = try? Activity<VPNActivityAttributes>.request(attributes: VPNActivityAttributes(), content: content, pushType: nil)
            }
            // Ended by the system (after 8 h) or swiped away: still listed, but updates do nothing.
            for old in all where !old.isLive { await old.end(nil, dismissalPolicy: .immediate) }
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
        guard let activity = Activity<VPNActivityAttributes>.activities.first(where: { $0.isLive }) else { return }
        var state = activity.content.state
        guard state.connected else { return }
        state.connected = false
        await activity.update(ActivityContent(state: state, staleDate: nil))
    }
}

@available(iOS 18.0, *)
private extension Activity {
    /// Shown and taking updates.
    var isLive: Bool { activityState == .active || activityState == .stale }
}
