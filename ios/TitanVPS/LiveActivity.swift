import ActivityKit
import Foundation
import NetworkExtension
import UIKit

/// The Live Activity (TitanWidgets draws it, iOS 18+), with the VPN on and off. On: the server,
/// «Подключено» with the timer, the speed, the ping and what the session used; "Переключаемся…"
/// while it connects again (another server, a ping's pause, iOS reconnecting), "Подключаемся…"
/// while it comes on. Off: «Выключено» with the server ⏻ would connect to (a pick in the app
/// shows there too); ‹ › then only move the pick. At once when turned off in the app (Выкл,
/// VPNController.disconnect), with the activity's ⏻, in Пункт управления or by Siri. Turned off
/// in iOS Settings, or on demand giving up, no code of ours runs (the app is suspended, the
/// tunnel can't): the activity still says «Подключено», its numbers stale, until the app runs
/// again (opened, or woken by an intent) and says «Выключено». The app left with the VPN still
/// on its way (its own connect, which gives its background time back once the start is asked
/// for, or iOS reconnecting): followed in background time until it settles. Work for a VPN
/// change runs in background time too. Any "Подключаемся…" / "Переключаемся…" but a ping's goes
/// stale a minute on (settleLimit): an outcome never seen reads «Состояние неизвестно», not a
/// connect with no end.
/// It ends only when Настройки → Dynamic Island is turned off, on Выйти, or with no key; one the
/// system ended (after 8 h) or that was swiped away comes back, on or off, when the app is
/// opened (sync), or with the next ⏻, ‹ ›, Пункт управления or Siri (a LiveActivityIntent may
/// start one in the background). Each VPN change also has Пункт управления read its toggle
/// again. Called from RootView and from those intents.
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
/// which need a server.
///
/// What the island can show of them: it shows the activity only "while your app isn't in use"
/// (HIG, Live Activities), and once the app is left iOS suspends it: nothing updates the
/// activity then. So the island's speed is what was sent on leaving the app (leaving: the
/// numbers of that moment), live for 15 s; after that the compact island shows the state's dot,
/// the expanded one and the Lock Screen the ping and the traffic under «Обновлено в …». A live
/// speed with the app in the background needs ActivityKit push updates from a server
/// (pushType: .token, NSSupportsLiveActivitiesFrequentUpdates), which this app doesn't have.
/// While the app is open the activity is seen only under Центр уведомлений pulled down over it
/// (the app inactive): a look every 2 s, sent there when what it shows changes; in the app
/// itself (active), where nothing shows it, only a new ping and one every 10 s, so iOS has no
/// unseen renders to throttle. And on every VPN change and intent.
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
        held { await apply(state, server: server, paused: paused, gen: gen) }
    }

    /// At launch and each time the app is opened: the activity and the toggle follow the VPN
    /// again, on or off. It may have changed meanwhile, the app can't start an activity in the
    /// background (connected after leaving the app), and the system ends one after 8 h.
    static func sync(_ state: VPNController.State, server: String?, paused: Bool) {
        VPNControl.reload()
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        let gen = generation
        held { await apply(state, server: server, paused: paused, gen: gen, opened: true) }
    }

    /// Выкл in the app (VPNController.disconnect, Выйти too): «Выключено» at once, as an intent's
    /// off. apply waits 2.5 s before it for a drop on the way to another server; this one is by
    /// hand, and the island shows the activity only once the app is left, often a moment after
    /// the tap (the app may be suspended before a wait ends).
    static func turnedOffInApp() {
        guard #available(iOS 18.0, *) else { return }
        // Work still waiting gives way (an apply about to say "Подключено").
        generation += 1
        held {
            // Выйти: the key is gone by now, and signedOut ends it.
            guard enabled, hasKey else { return }
            await showOff(start: false)
        }
    }

    /// Work for a VPN change in background time: the app left during apply's wait (or its look
    /// at the profile) would else be suspended with the activity saying what it said before.
    /// Free while the app is in the foreground.
    private static func held(_ work: @escaping @MainActor () async -> Void) {
        let time = BackgroundTime("Live Activity: VPN change")
        time.begin()
        Task { @MainActor in
            await work()
            time.end()
        }
    }

    /// Turned on without the app's screen (Пункт управления, Siri, the activity's ‹ › ⏻): their
    /// intent may start one even with the app in the background. Not up yet (start's 12 s ran
    /// out: a slow network, a plugin's server) or down again already: followed until it settles.
    static func turnedOn() async {
        guard #available(iOS 18.0, *) else { return }
        // Before the look: an off that comes meanwhile is newer and wins.
        generation += 1
        let gen = generation
        guard let since = await TunnelSwitch.connectedSince() else {
            // After the intent returns, in background time (Siri isn't kept waiting): iOS would
            // else suspend the app with the activity still saying "Подключаемся…".
            let time = BackgroundTime("Live Activity: VPN settling")
            time.begin()
            Task { @MainActor in
                await follow()
                time.end()
            }
            return
        }
        await apply(.connected(since: since), server: nil, paused: false, gen: gen)
    }

    /// Being turned on without the app's screen: «Выключено» becomes "Подключаемся…" at once
    /// (the tunnel takes a few seconds). Shown on: left as it is.
    static func comingOn() async {
        guard #available(iOS 18.0, *), enabled, hasKey else { return }
        generation += 1
        await showConnecting(fromOffOnly: true, staleDate: Date().addingTimeInterval(settleLimit))
    }

    /// The activity's ‹ › picked another server: its name at once, "Переключаемся…" until it
    /// is up (turnedOn or vpnChanged then show it connected).
    static func switching(to name: String) async {
        guard #available(iOS 18.0, *), enabled else { return }
        generation += 1
        guard let activity = live() else { return }
        let (flag, rest) = name.splitFlag
        let state = VPNActivityAttributes.ContentState(server: rest, flag: flag, connected: false, phase: .switching)
        await activity.update(ActivityContent(state: state, staleDate: Date().addingTimeInterval(settleLimit)))
    }

    /// Turned off without the app's screen (Пункт управления, Siri, ⏻), or a switch that ended
    /// with the VPN down: «Выключено». Only reached from an intent (a LiveActivityIntent may
    /// start one in the background): none shown (ended after 8 h, swiped away) starts one.
    static func turnedOff() async {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        guard enabled, hasKey else { return await endEvery() }
        await showOff(start: true)
    }

    /// ‹ › while off moved the pick: the activity shows it (⏻ connects to it). One left saying
    /// "Подключено" by a VPN that went off without the app says «Выключено» now; none shown
    /// (the tap was on the Lock Screen's ended card) starts one, as turnedOff.
    static func picked() async {
        guard #available(iOS 18.0, *) else { return }
        generation += 1
        guard enabled, hasKey else { return await endEvery() }
        await showOff(start: true)
    }

    /// The pick changed in the app with the VPN off (Главная, a refreshed subscription,
    /// Сбросить настройки): «Выключено» names it, so ⏻ connects to the server shown. Only a card
    /// saying «Выключено»: one on its way follows the VPN itself, and just after launch the
    /// app's state may lag the profile's (iOS may have it up).
    static func pickChanged() async {
        guard #available(iOS 18.0, *), enabled, hasKey else { return }
        guard !(await TunnelSwitch.isOn()), live()?.content.state.shown == .off else { return }
        await showOff(start: false)
    }

    /// Leaving the app (inactive, then background) with the VPN up: the numbers of this moment,
    /// whatever went out last. The island shows the activity only now, and nothing updates it
    /// once the app is suspended: this is its speed for the next 15 s. With the activity still
    /// saying "Подключаемся…" / "Переключаемся…": the VPN followed until it settles, in
    /// background time, as after an intent (Вкл and leaving at once, iOS reconnecting).
    static func leaving() {
        guard #available(iOS 18.0, *), enabled else { return }
        guard VPNController.current?.isConnected == true else { return followLeft() }
        // Inactive, then background a moment later: one is enough.
        guard Date().timeIntervalSince(leftAt) >= 1, Date().timeIntervalSince(sentAt) >= 1 else { return }
        leftAt = Date()
        let time = BackgroundTime("Live Activity: leaving")
        time.begin()
        Task { @MainActor in
            _ = await tick(force: true)
            time.end()
        }
    }

    /// leaving's follow under way (inactive, then background: one is enough).
    private static var followingLeft = false

    @available(iOS 18.0, *)
    private static func followLeft() {
        guard !followingLeft, let phase = live()?.content.state.shown, phase == .connecting || phase == .switching else { return }
        followingLeft = true
        let time = BackgroundTime("Live Activity: VPN settling")
        time.begin()
        Task { @MainActor in
            await follow()
            followingLeft = false
            time.end()
        }
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
        case .connecting:
            // The app's own connect, or iOS reconnecting (a network change, on demand after the
            // tunnel fell): no background time holds the app until it is up (connect gives its
            // own back once the start is asked for), so leaving follows it; never seen to
            // settle, it goes stale as an intent's does.
            await showConnecting(fromOffOnly: false, staleDate: Date().addingTimeInterval(settleLimit))
        case .disconnecting where paused, .off where paused:
            // Off for a ping: the app brings it back (pauseTime). No stale date, a Пинг may run
            // longer than settleLimit.
            await showConnecting(fromOffOnly: false, staleDate: nil)
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
    /// reconnecting), "Подключаемся…" from off. No numbers meanwhile. staleDate: settleLimit on
    /// (the app may be suspended before the VPN settles), nil for a ping's pause. Already on its
    /// way: only the stale date changes (a ping's pause has none, the connect after it one).
    @available(iOS 18.0, *)
    private static func showConnecting(fromOffOnly: Bool, staleDate: Date?) async {
        guard let activity = live() else { return }
        let now = activity.content.state
        let phase: VPNActivityAttributes.ContentState.Phase
        switch now.shown {
        case .off:
            phase = .connecting
        case .on:
            guard !fromOffOnly else { return }
            phase = .switching
        case .connecting, .switching:
            guard !fromOffOnly, activity.content.staleDate != staleDate else { return }
            phase = now.shown
        }
        let state = VPNActivityAttributes.ContentState(server: now.server, flag: now.flag, connected: false, phase: phase)
        await activity.update(ActivityContent(state: state, staleDate: staleDate))
    }

    /// "Подключаемся…" / "Переключаемся…" (an intent's or the app's, not a ping's) goes stale this
    /// long after: no update by then (the app suspended before the VPN settled) and the widget
    /// says it doesn't know. Longer than start's 12 s and follow's 20 s.
    private static let settleLimit: TimeInterval = 60

    /// The VPN wasn't up when turnedOn looked, or the app is left with it on its way (leaving):
    /// its status every 0.5 s for up to 20 s more, then the activity says on or «Выключено».
    /// Only while it still says what it said here: an off, a switch or the app's own look since
    /// has seen to it. Still not up after that: left as it is, it goes stale (settleLimit).
    @available(iOS 18.0, *)
    private static func follow() async {
        // The manager kept for the whole watch: its connection's status follows the VPN.
        guard let before = live()?.content.state, let manager = try? await TunnelSwitch.manager() else { return }
        var offSince: Date?
        for _ in 0..<40 {
            guard enabled, hasKey, let now = live()?.content.state,
                  now.shown == before.shown, now.server == before.server else { return }
            let connection = manager.connection
            switch connection.status {
            case .connected:
                generation += 1
                return await apply(.connected(since: connection.connectedDate ?? Date()), server: nil, paused: false, gen: generation)
            case .disconnected, .invalid:
                // Down for 2 s: not just between two servers, nor on demand starting it again,
                // nor off for a ping (the app brings it back).
                let at = offSince ?? Date()
                offSince = at
                if Date().timeIntervalSince(at) >= 2, VPNController.current?.connectsUnderWay != true,
                   AppStore.current?.vpnPaused != true {
                    generation += 1
                    return await showOff(start: false)
                }
            default:
                offSince = nil
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
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
    private static var leftAt = Date.distantPast
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
    /// force: leaving the app, sent whatever went out last.
    @available(iOS 18.0, *)
    private static func tick(force: Bool = false) async -> Bool {
        guard let activity = live(), activity.content.state.shown == .on, let name = runningName,
              case .connected(let since)? = VPNController.current?.state else { return false }
        notePings(for: name)
        if !force { probeIfDue(name) }
        let state = onState(name, since: since)
        if !force, !due(state) { return true }
        await send(state, to: activity)
        return true
    }

    /// At least every 10 s. Under Центр уведомлений (the app inactive) the activity can be seen:
    /// also when what it shows changes. In the app (active) nothing shows it, the island
    /// included: also on a new ping, not on each change of the speed.
    @available(iOS 18.0, *)
    private static func due(_ state: VPNActivityAttributes.ContentState) -> Bool {
        if Date().timeIntervalSince(sentAt) >= 10 { return true }
        let now = numbers(state)
        if UIApplication.shared.applicationState == .active { return now.ping != shownNumbers?.ping }
        return now != shownNumbers
    }

    /// The tunnel's counters now: the session's traffic, and the speed since the last look
    /// (when it was a moment ago). A new connection, or a new interface, counts from the
    /// interface's own zero.
    private static func look(since: Date) {
        guard let now = TrafficMonitor.counters() else { return }
        let date = Date()
        guard let prev = lastLook, prev.name == now.name, let s = session, s.since == since else {
            lastLook = (now.name, now.down, now.up, date)
            session = (since, UInt64(now.down), UInt64(now.up))
            speed = nil
            return
        }
        let seconds = date.timeIntervalSince(prev.at)
        // A moment after the last (leaving: inactive, then background): its numbers still hold.
        if seconds >= 0 && seconds < 0.5 { return }
        lastLook = (now.name, now.down, now.up, date)
        // The counters are 32-bit: a wrapping difference (right unless 4 GB went between two looks).
        let d = UInt64(now.down &- prev.down)
        let u = UInt64(now.up &- prev.up)
        session = (since, s.down + d, s.up + u)
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
