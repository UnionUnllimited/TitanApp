import ActivityKit
import Foundation

/// The Live Activity while the VPN is on (TitanWidgets draws it, iOS 18+): it starts when the
/// VPN connects, shows "Переключаемся…" while it connects again (another server, a ping's
/// pause) and ends when the VPN is off. Each VPN change also has Пункт управления read its
/// toggle again. Called from RootView and from the intents (Пункт управления, Siri, the
/// activity's own ‹ ›), which may start one while the app is in the background. Настройки →
/// Dynamic Island turns it off.
@MainActor
enum LiveActivityController {
    /// Настройки → Dynamic Island (on unless turned off there).
    nonisolated static let key = "liveActivity"
    nonisolated static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }

    /// Bumped on each change: work still waiting from an older one gives way.
    private static var generation = 0

    /// Live Activities are allowed for the app in iOS Settings.
    static var allowedInSettings: Bool {
        guard #available(iOS 18.0, *) else { return false }
        return ActivityAuthorizationInfo().areActivitiesEnabled
    }

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
        Task { await endIfOff(since: gen) }
    }

    /// As endIfOff, awaited: an intent's process may be suspended as soon as it returns.
    static func endIfOffNow() async {
        guard #available(iOS 18.0, *) else { return }
        await endIfOff(since: generation)
    }

    @available(iOS 18.0, *)
    private static func endIfOff(since gen: Int) async {
        let stale = Activity<VPNActivityAttributes>.activities
        guard !stale.isEmpty, !(await TunnelSwitch.isOn()), gen == generation else { return }
        for activity in stale { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    /// Turned on without the app's screen (Пункт управления, Siri, the activity's ‹ ›): their
    /// intent may start one even with the app in the background.
    static func turnedOn() async {
        guard #available(iOS 18.0, *) else { return }
        guard let since = await TunnelSwitch.connectedSince() else { return }
        generation += 1
        await apply(.connected(since: since), server: nil, paused: false, gen: generation)
    }

    /// The activity's ‹ › picked another server: its name at once, "Переключаемся…" until it
    /// is up (turnedOn or vpnChanged then show it connected).
    static func switching(to name: String) async {
        guard #available(iOS 18.0, *), enabled else { return }
        generation += 1
        guard let activity = Activity<VPNActivityAttributes>.activities.first(where: { $0.isLive }) else { return }
        let (flag, rest) = name.splitFlag
        let state = VPNActivityAttributes.ContentState(server: rest, flag: flag, connected: false)
        await activity.update(ActivityContent(state: state, staleDate: nil))
    }

    /// Turned off without the app's screen (Пункт управления, Siri), or in Настройки.
    static func endAll() async {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        for activity in Activity<VPNActivityAttributes>.activities { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    @available(iOS 18.0, *)
    private static func apply(_ state: VPNController.State, server: String?, paused: Bool, gen: Int) async {
        // Turned off in Настройки: none at all.
        guard enabled else {
            for activity in Activity<VPNActivityAttributes>.activities { await activity.end(nil, dismissalPolicy: .immediate) }
            return
        }
        switch state {
        case .connected:
            // The server the tunnel runs: it may have been started from iOS Settings.
            let name = await TunnelSwitch.serverName() ?? server ?? "Titan VPS"
            guard gen == generation else { return }
            let (flag, rest) = name.splitFlag
            let content = ActivityContent(state: VPNActivityAttributes.ContentState(
                server: rest, flag: flag, connected: true), staleDate: nil)
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
