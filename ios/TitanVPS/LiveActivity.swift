import ActivityKit
import Foundation
import UIKit

/// The Live Activity (TitanWidgets draws it, iOS 18+), with the VPN on and off. On: the server,
/// «Подключено» with the timer, the speed, the ping and what the session used; "Переключаемся…"
/// while it connects again (another server, a ping's pause), "Подключаемся…" while it comes on.
/// Off (in the app, the activity's ⏻, Пункт управления, Siri, iOS Settings, the tunnel giving
/// up): «Выключено» with the server ⏻ would connect to; ‹ › then only move the pick. It ends
/// only when Настройки → Dynamic Island is turned off, on Выйти, or with no key; one the system
/// ended (after 8 h) or that was swiped away comes back, on or off, when the app is opened (sync).
/// Each VPN change also has Пункт управления read its toggle again. Called from RootView and
/// from the intents (Пункт управления, Siri, the activity's ‹ › ⏻), which may start one while
/// the app is in the background.
///
/// Who sends the numbers: the app, not the tunnel. Activity (iOS 16.1+) is documented as what
/// starts, updates and ends a Live Activity "from within your app"; whatever an extension may
/// compile against, its process sees an empty Activity.activities (Apple Developer Forums
/// 735382: the widget extension's own), and the packet tunnel is another bundle, with ~50 MB and
/// no ActivityKit linked: it can't update ours. Nor hand its counters over: there is no App
/// Group (VPNController), and none is needed, the utun's counters read the same in the app
/// (TrafficMonitor.counters). Local updates have no documented budget, but iOS throttles
/// frequent ones and may drop them from the background ("we purposely discourage and throttle
/// update frequencies…", a Frameworks Engineer, forums 748569: the Lock Screen and SpringBoard
/// have a say); many updates are for ActivityKit pushes (NSSupportsLiveActivitiesFrequentUpdates),
/// which need a server. So: every 2 s while the app is open (not in the background) when what
/// the activity shows would change, at least every 10 s, and on every VPN change and intent.
/// Each update carries its time and a stale date 15 s on: once iOS marks it stale the widget
/// drops the speed and keeps the ping and the traffic under that time, so a frozen speed never
/// reads as live. The ping: the newest of Проверить соединение's result, the running server's
/// Пинг and a probe of our own (one HEAD through the tunnel, at most once a minute while the app
/// is open); none older than 2 min goes out.
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

    /// A key is added (with none, after Выйти or before the first, there is no activity). The
    /// store's while the app runs, else what it saved (an intent's background launch).
    private static var hasKey: Bool {
        if let store = AppStore.current { return store.subscription != nil }
        return UserDefaults.standard.data(forKey: "titan.subscription") != nil
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
    /// again, on or off. It may have changed meanwhile, the app can't start an activity in the
    /// background (connected after leaving the app), and the system ends one after 8 h.
    static func sync(_ state: VPNController.State, server: String?, paused: Bool) {
        VPNControl.reload()
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        let gen = generation
        Task { await apply(state, server: server, paused: paused, gen: gen, opened: true) }
    }

    /// Turned on without the app's screen (Пункт управления, Siri, the activity's ‹ › ⏻): their
    /// intent may start one even with the app in the background.
    static func turnedOn() async {
        guard #available(iOS 18.0, *) else { return }
        guard let since = await TunnelSwitch.connectedSince() else { return }
        generation += 1
        await apply(.connected(since: since), server: nil, paused: false, gen: generation)
    }

    /// Being turned on without the app's screen: «Выключено» becomes "Подключаемся…" at once
    /// (the tunnel takes a few seconds). Shown on: left as it is.
    static func comingOn() async {
        guard #available(iOS 18.0, *), enabled, hasKey else { return }
        generation += 1
        await showConnecting(fromOffOnly: true)
    }

    /// The activity's ‹ › picked another server: its name at once, "Переключаемся…" until it
    /// is up (turnedOn or vpnChanged then show it connected).
    static func switching(to name: String) async {
        guard #available(iOS 18.0, *), enabled else { return }
        generation += 1
        guard let activity = live() else { return }
        let (flag, rest) = name.splitFlag
        let state = VPNActivityAttributes.ContentState(server: rest, flag: flag, connected: false, phase: .switching)
        await activity.update(ActivityContent(state: state, staleDate: nil))
    }

    /// Turned off without the app's screen (Пункт управления, Siri, ⏻), or a switch that ended
    /// with the VPN down: «Выключено».
    static func turnedOff() async {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        guard enabled, hasKey else { return await endEvery() }
        await showOff(start: false)
    }

    /// ‹ › while off moved the pick: the activity shows it (⏻ connects to it). One left saying
    /// "Подключено" by a VPN that went off without the app says «Выключено» now.
    static func picked() async {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        guard enabled, hasKey else { return await endEvery() }
        await showOff(start: false)
    }

    /// Turned off in Настройки: none at all.
    static func endAll() async {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        await endEvery()
    }

    /// Выйти (AppStore.logout): the activity goes with the key.
    static func signedOut() {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        Task { await endEvery() }
    }

    @available(iOS 18.0, *)
    private static func apply(_ state: VPNController.State, server: String?, paused: Bool, gen: Int, opened: Bool = false) async {
        // Turned off in Настройки, or no key: none at all.
        guard enabled, hasKey else { return await endEvery() }
        switch state {
        case .connected(let since):
            // The server the tunnel runs: it may have been started from iOS Settings.
            let name = await TunnelSwitch.serverName() ?? server ?? "Titan VPS"
            guard gen == generation else { return }
            await showOn(name, since: since)
        case .connecting, .disconnecting where paused, .off where paused:
            await showConnecting(fromOffOnly: false)
        case .disconnecting, .off, .error:
            // Another server: off for a moment, then on again; the activity stays on through it.
            // Opened with the VPN off: at once.
            if !opened || state == .disconnecting { try? await Task.sleep(nanoseconds: 2_500_000_000) }
            guard gen == generation, VPNController.current?.connectsUnderWay != true else { return }
            // Just launched, the app's state may not be the profile's yet (iOS may have it up).
            let on = await TunnelSwitch.isOn()
            guard !on, gen == generation else { return }
            await showOff(start: opened)
        }
    }

    /// Up on [name]: the numbers now; one is started if none is shown.
    @available(iOS 18.0, *)
    private static func showOn(_ name: String, since: Date) async {
        runningName = name
        notePings(for: name)
        let state = onState(name, since: since)
        let all = Activity<VPNActivityAttributes>.activities
        let live = all.filter { $0.isLive }
        if let activity = live.first {
            await send(state, to: activity)
            for extra in live.dropFirst() { await extra.end(nil, dismissalPolicy: .immediate) }
        } else if ActivityAuthorizationInfo().areActivitiesEnabled {
            sent(state)
            _ = try? Activity<VPNActivityAttributes>.request(attributes: VPNActivityAttributes(), content: content(state), pushType: nil)
        }
        // Ended by the system (after 8 h) or swiped away: still listed, but updates do nothing.
        for old in all where !old.isLive { await old.end(nil, dismissalPolicy: .immediate) }
        startTicking()
    }

    /// Not up for a moment: "Переключаемся…" from on (another server, a ping's pause, iOS
    /// reconnecting), "Подключаемся…" from off. No numbers meanwhile.
    @available(iOS 18.0, *)
    private static func showConnecting(fromOffOnly: Bool) async {
        guard let activity = live() else { return }
        let now = activity.content.state
        let phase: VPNActivityAttributes.ContentState.Phase
        switch now.shown {
        case .off: phase = .connecting
        case .on where !fromOffOnly: phase = .switching
        default: return
        }
        let state = VPNActivityAttributes.ContentState(server: now.server, flag: now.flag, connected: false, phase: phase)
        await activity.update(ActivityContent(state: state, staleDate: nil))
    }

    /// Off: «Выключено» with the pick, the server ⏻ starts. start: one is started if none is
    /// shown (the app was opened: iOS allows it in the foreground).
    @available(iOS 18.0, *)
    private static func showOff(start: Bool) async {
        runningName = nil
        let (flag, rest) = (VPNIntents.pickedName ?? "Titan VPS").splitFlag
        let state = VPNActivityAttributes.ContentState(server: rest, flag: flag, connected: false, phase: .off)
        let all = Activity<VPNActivityAttributes>.activities
        let live = all.filter { $0.isLive }
        if let activity = live.first {
            if activity.content.state != state { await activity.update(ActivityContent(state: state, staleDate: nil)) }
            for extra in live.dropFirst() { await extra.end(nil, dismissalPolicy: .immediate) }
        } else if start, ActivityAuthorizationInfo().areActivitiesEnabled {
            _ = try? Activity<VPNActivityAttributes>.request(attributes: VPNActivityAttributes(),
                                                             content: ActivityContent(state: state, staleDate: nil), pushType: nil)
        }
        for old in all where !old.isLive { await old.end(nil, dismissalPolicy: .immediate) }
    }

    @available(iOS 18.0, *)
    private static func endEvery() async {
        runningName = nil
        for activity in Activity<VPNActivityAttributes>.activities { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    /// Shown and taking updates.
    @available(iOS 18.0, *)
    private static func live() -> Activity<VPNActivityAttributes>? {
        Activity<VPNActivityAttributes>.activities.first { $0.isLive }
    }

    // MARK: The numbers

    /// The server the activity shows up (with its flag); nil while it isn't.
    private static var runningName: String?
    /// The utun at the last look, for the speed.
    private static var lastLook: (name: String, down: UInt32, up: UInt32, at: Date)?
    /// What the connection that came up at [since] has used.
    private static var session: (since: Date, down: UInt64, up: UInt64)?
    private static var speed: (down: Double, up: Double, at: Date)?
    /// The newest ping of a server (by name, with its flag) and when it was measured.
    private static var lastPing: (server: String, ms: Int, at: Date)?
    /// The store's check and pings at the last look, and when: a change is a new measurement.
    private static var seenCheck = ConnectionCheck.Status.idle
    private static var seenPings: [String: Int] = [:]
    private static var seenAt = Date.distantPast
    private static var lastProbe = Date.distantPast
    private static var probing = false
    /// What the activity shows of the numbers, and when they were sent.
    private static var shownNumbers: Shown?
    private static var sentAt = Date.distantPast
    private static var ticker: Task<Void, Never>?

    /// The numbers as the widget writes them: an update goes out only when these change.
    private struct Shown: Equatable {
        let down, up, traffic: String?
        let ping: Int?
    }

    /// While the app is open (in the foreground or under Пункт управления, not in the
    /// background) and the VPN up: a look every 2 s. Started again by sync and each connect.
    @available(iOS 18.0, *)
    private static func startTicking() {
        guard ticker == nil, ticking else { return }
        ticker = Task { @MainActor in
            defer { ticker = nil }
            repeat {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard ticking, await tick() else { return }
            } while !Task.isCancelled
        }
    }

    private static var ticking: Bool {
        enabled && UIApplication.shared.applicationState != .background && VPNController.current?.isConnected == true
    }

    /// The numbers to the activity if it shows the VPN up; false when there's nothing to tick for.
    @available(iOS 18.0, *)
    private static func tick() async -> Bool {
        guard let activity = live(), activity.content.state.shown == .on, let name = runningName,
              case .connected(let since)? = VPNController.current?.state else { return false }
        notePings(for: name)
        probeIfDue(name)
        let state = onState(name, since: since)
        if numbers(state) == shownNumbers, Date().timeIntervalSince(sentAt) < 10 { return true }
        await send(state, to: activity)
        return true
    }

    /// The tunnel's counters now: the session's traffic, and the speed since the last look
    /// (when it was a moment ago). A new connection, or a new interface, counts from the
    /// interface's own zero.
    private static func look(since: Date) {
        guard let now = TrafficMonitor.counters() else { return }
        let date = Date()
        defer { lastLook = (now.name, now.down, now.up, date) }
        guard let prev = lastLook, prev.name == now.name, let s = session, s.since == since else {
            session = (since, UInt64(now.down), UInt64(now.up))
            speed = nil
            return
        }
        // The counters are 32-bit: a wrapping difference (right unless 4 GB went between two looks).
        let d = UInt64(now.down &- prev.down)
        let u = UInt64(now.up &- prev.up)
        session = (since, s.down + d, s.up + u)
        let seconds = date.timeIntervalSince(prev.at)
        if seconds >= 0.5 && seconds <= 5 {
            speed = (Double(d) / seconds, Double(u) / seconds, date)
        } else {
            speed = nil
        }
    }

    @available(iOS 18.0, *)
    private static func onState(_ name: String, since: Date) -> VPNActivityAttributes.ContentState {
        look(since: since)
        let (flag, rest) = name.splitFlag
        let now = Date()
        var state = VPNActivityAttributes.ContentState(server: rest, flag: flag, connected: true, phase: .on, since: since)
        if let s = session, s.since == since { state.traffic = Int64(clamping: s.down + s.up) }
        if let sp = speed, now.timeIntervalSince(sp.at) < 5 {
            state.down = sp.down
            state.up = sp.up
        }
        if let p = lastPing, p.server == name, now.timeIntervalSince(p.at) < 120 { state.ping = p.ms }
        state.statsAt = now
        return state
    }

    @available(iOS 18.0, *)
    private static func numbers(_ state: VPNActivityAttributes.ContentState) -> Shown {
        Shown(down: state.down.map(TrafficText.speed), up: state.up.map(TrafficText.speed),
              traffic: state.traffic.map(TrafficText.bytes), ping: state.ping)
    }

    /// The numbers go stale 15 s after they were read: the widget then hides the speed.
    @available(iOS 18.0, *)
    private static func content(_ state: VPNActivityAttributes.ContentState) -> ActivityContent<VPNActivityAttributes.ContentState> {
        ActivityContent(state: state, staleDate: state.statsAt?.addingTimeInterval(15))
    }

    @available(iOS 18.0, *)
    private static func sent(_ state: VPNActivityAttributes.ContentState) {
        shownNumbers = numbers(state)
        sentAt = Date()
    }

    @available(iOS 18.0, *)
    private static func send(_ state: VPNActivityAttributes.ContentState, to activity: Activity<VPNActivityAttributes>) async {
        sent(state)
        await activity.update(content(state))
    }

    /// New measurements of the running server since the last look: Проверить соединение's
    /// result (through the tunnel, so its delay) or its Пинг. Only when that look was a moment
    /// ago: what changed while the app was away (or before the first look) may be any age.
    private static func notePings(for name: String) {
        guard let store = AppStore.current else { return }
        let now = Date()
        let recent = now.timeIntervalSince(seenAt) <= 10
        seenAt = now
        let check = store.check
        if recent, check != seenCheck, case .ok(let r) = check { lastPing = (name, r.ms, now) }
        seenCheck = check
        let pings = store.pings
        if recent, let id = store.subscription?.servers.first(where: { $0.name == name })?.id,
           let ms = pings[id], ms >= 0, seenPings[id] != ms {
            lastPing = (name, ms, now)
        }
        seenPings = pings
    }

    /// No ping of the server for a minute: one HEAD through the tunnel (ConnectionCheck.probe),
    /// not alongside Проверить соединение or a Пинг.
    private static func probeIfDue(_ name: String) {
        let now = Date()
        guard !probing, now.timeIntervalSince(lastProbe) >= 60,
              lastPing.map({ $0.server != name || now.timeIntervalSince($0.at) >= 60 }) ?? true,
              let store = AppStore.current, store.check != .checking, !store.pingBusy else { return }
        probing = true
        lastProbe = now
        Task { @MainActor in
            let ms = await ConnectionCheck.probe()
            probing = false
            // Still up on that server: else it measured a switch.
            guard let ms, runningName == name, VPNController.current?.isConnected == true else { return }
            lastPing = (name, ms, Date())
        }
    }
}

@available(iOS 18.0, *)
private extension Activity {
    /// Shown and taking updates.
    var isLive: Bool { activityState == .active || activityState == .stale }
}
