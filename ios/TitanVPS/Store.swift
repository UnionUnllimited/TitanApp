import Foundation
import Network
import SwiftUI
import UserNotifications

@MainActor
final class AppStore: ObservableObject {
    @Published var subscription: Subscription? { didSet { indexServers() } }
    @Published var selectedId: String?
    @Published var busy = false
    @Published var message: String?
    @Published var pings: [String: Int] = [:]
    /// Why the last ping of a server failed (Xray's error), shown under it.
    @Published var pingErrors: [String: String] = [:]
    @Published var pinging = false
    /// Devices and their limit from the bot (nil until loaded).
    @Published var account: AccountAPI.Info?
    /// A short note at the top of the screen ("Подписка обновлена").
    @Published var toast: Toast?
    /// Проверить соединение: the last check through the running VPN.
    @Published private(set) var check: ConnectionCheck.Status = .idle
    /// A ping (or another check that needs the VPN off) is running: Выкл | Вкл can't be moved.
    @Published private(set) var pingBusy = false
    /// The VPN was turned off for a ping and comes back by itself after it. Until it is back the
    /// app has background time: left meanwhile, it still brings the VPN back.
    @Published private(set) var vpnPaused = false {
        didSet { if vpnPaused { pauseTime.begin() } else { pauseTime.end() } }
    }
    private let pauseTime = BackgroundTime("VPN back after a ping")
    /// Servers in the running "Пинг" (for its progress ring).
    @Published private(set) var pingTotal = 0
    /// Тарифы (PaywallView) on screen and why it opened; RootView shows it.
    @Published var paywall: PaywallReason?
    /// Bumped to close the tabs' own sheets (Личный кабинет, Устройства, О приложении): Тарифы
    /// from a reminder then opens over the app, not under one of them (RootView).
    @Published var closeSheets = 0

    /// Favourite servers, by name (ids change with the subscription).
    @Published private(set) var favorites: Set<String> = []
    /// On Wi-Fi: bypass servers (Обходы) work only over mobile internet.
    @Published private(set) var onWifi = false
    /// Servers the iPhone app can't run yet, by id: worked out once per subscription (it parses
    /// each server's whole config), not on every redraw of Главная. Changes only with
    /// `subscription`, which publishes it.
    private(set) var unsupported: Set<String> = []

    /// Set by the app: pings turn the VPN off while they run and back on after.
    weak var vpn: VPNController?
    /// Pings (and checks) holding the VPN off: each gives its hold back when it ends, or at
    /// once when it is stopped (a libXray call in flight can't be cut short).
    private var holds: Set<UUID> = []
    private var pingAllHold: UUID?
    private var oneHolds: [String: UUID] = [:]
    /// withVPNPaused's, once the VPN is off for them (leftApp gives them back).
    private var checkHolds: Set<UUID> = []
    private var pausing: Task<Void, Never>?
    /// The reconnect after the last ping; a new ping waits for it before pausing again.
    private var resuming: Task<Void, Never>?
    private var resumeOn: Server?
    /// VPNController.offsByHand when the pause began: an off by hand since (Siri, ⏻, Выйти)
    /// keeps the VPN off after the ping.
    private var offsAtPause = 0

    private let subKey = "titan.subscription"
    private let selKey = "titan.selected"
    private let favKey = "titan.favorites"
    /// The cabinet's «Ваш ID» and the key it is for (its keyHash).
    private let clientIdKey = "titan.clientId"
    private let pathMonitor = NWPathMonitor()
    /// Bumped by each new key and by Выйти: a fetch begun before either is dropped when it
    /// answers, so a deleted or replaced key doesn't come back.
    private var generation = 0

    /// The app's own, while it runs: the Live Activity's ‹ › move the pick here (VPNIntents).
    static weak var current: AppStore?

    init() {
        if let data = UserDefaults.standard.data(forKey: subKey),
           let sub = try? JSONDecoder().decode(Subscription.self, from: data) {
            subscription = sub
        }
        // A class's own init doesn't run didSet.
        indexServers()
        selectedId = UserDefaults.standard.string(forKey: selKey)
        favorites = Set(UserDefaults.standard.stringArray(forKey: favKey) ?? [])
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // With the VPN on the path is the tunnel; the Wi-Fi interface is still listed.
            let wifi = path.status == .satisfied && path.availableInterfaces.contains { $0.type == .wifi }
            Task { @MainActor in if self?.onWifi != wifi { self?.onWifi = wifi } }
        }
        pathMonitor.start(queue: DispatchQueue(label: "titan.path"))
        UNUserNotificationCenter.current().delegate = NotificationPresenter.shared
        Self.current = self
    }

    /// Support: the subscription's support-url, else the bot.
    var supportURL: URL {
        subscription?.info.supportUrl.flatMap { URL(string: $0) }.flatMap { $0.scheme == nil ? nil : $0 } ?? Config.telegram
    }

    /// The cabinet showed «Ваш ID»: kept for this key, it names the account to support.
    func rememberClientId(_ id: String) {
        guard let key = subscription?.keyHash, clientId != id else { return }
        UserDefaults.standard.set(["key": key, "id": id], forKey: clientIdKey)
    }

    /// «Ваш ID» from the cabinet, if it was shown for this key.
    var clientId: String? {
        guard let saved = UserDefaults.standard.dictionary(forKey: clientIdKey) as? [String: String],
              let key = subscription?.keyHash, saved["key"] == key else { return nil }
        return saved["id"]?.nilIfEmpty
    }

    /// Удалить аккаунт: the request copied for support. The account by the cabinet's ID, else
    /// by the bot's username, else by the key's short hash (docs/BACKEND-APPSTORE.md).
    var deletionRequest: String {
        let id = clientId ?? account?.username ?? subscription.map { "\($0.keyHash) (хеш ключа)" } ?? "—"
        return "Прошу удалить мой аккаунт Titan VPS. ID: \(id)"
    }

    /// Удалить аккаунт through the bot (Config.deletionEndpoint, docs/BACKEND-APPSTORE.md): true
    /// once it answered 2xx. No endpoint yet or no answer: false, the request goes to support.
    func deleteOnServer() async -> Bool {
        guard let endpoint = Config.deletionEndpoint, let sub = subscription else { return false }
        var body: [String: Any] = ["key": sub.url, "appAccountToken": sub.appAccountToken.uuidString.lowercased()]
        if let id = clientId { body["clientId"] = id }
        guard let (_, code) = await BotAPI.post(endpoint, body) else { return false }
        return (200..<300).contains(code)
    }

    private func indexServers() {
        unsupported = Set((subscription?.servers ?? []).filter { s in
            Plugins.endpoint(s).map { !$0.kind.supportedOnIOS } ?? false
        }.map(\.id))
    }

    func isFavorite(_ s: Server) -> Bool { favorites.contains(s.name) }

    func toggleFavorite(_ s: Server) {
        if favorites.contains(s.name) { favorites.remove(s.name) } else { favorites.insert(s.name) }
        UserDefaults.standard.set(Array(favorites), forKey: favKey)
    }

    /// A bypass server picked while on Wi-Fi: not allowed (same as Android), except in Технический режим.
    func blockedOnWifi(_ s: Server?) -> Bool { bypassLockedOnWifi && s.map(ServerGroup.isBypass) == true }
    var bypassLockedOnWifi: Bool { onWifi && !UserDefaults.standard.bool(forKey: TechMode.key) }

    /// Where to go from a bypass server on Wi-Fi: a favourite regular server, else the first.
    var firstRegular: Server? {
        let regular = (subscription?.servers ?? []).filter { !ServerGroup.isBypass($0) && !ServerGroup.isHidden($0) }
        return regular.first(where: isFavorite) ?? regular.first
    }

    /// Theme, favourites, Автоподключение, Не отключать VPN and the picked server back to the
    /// start; the key stays.
    func resetSettings() {
        favorites = []
        UserDefaults.standard.removeObject(forKey: favKey)
        UserDefaults.standard.set("system", forKey: "theme")
        UserDefaults.standard.set(false, forKey: "autoconnect")
        UserDefaults.standard.set(true, forKey: VPNController.keepAliveKey)
        select(subscription?.servers.first?.id)
        message = "Настройки сброшены"
    }

    var selected: Server? {
        guard let sub = subscription else { return nil }
        return sub.servers.first { $0.id == selectedId } ?? sub.servers.first
    }

    /// Adds the key; true when the subscription loaded (the caller then connects).
    @discardableResult
    func activate(_ text: String) async -> Bool {
        guard let url = SubscriptionAPI.findLink(text) else {
            message = "Это не ключ Titan VPS"
            return false
        }
        // A fetch of the old key still under way doesn't overwrite this one.
        generation += 1
        guard await load(url) else { return false }
        await loadAccount()
        return true
    }

    /// quiet: at launch / in the background with a saved subscription: no alert on failure
    /// (the network may be busy with the VPN coming up), one more try a bit later.
    @discardableResult
    func refresh(quiet: Bool = false) async -> Bool {
        guard let url = subscription?.url else { return false }
        let started = generation
        var ok = await load(url, quiet: quiet)
        if !ok && quiet && started == generation {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            // Signed out or another key during the wait: not the old one again.
            guard started == generation else { return false }
            ok = await load(url, quiet: true)
        }
        guard started == generation else { return false }
        await loadAccount()
        return ok
    }

    /// Обновить подписку by hand: says how it went in a note at the top (no alert to dismiss).
    func refreshByHand() async {
        guard !busy, let url = subscription?.url else { return }
        let started = generation
        let ok = await load(url, quiet: true)
        // Signed out or another key meanwhile: nothing to say about the old one.
        guard started == generation else { return }
        if ok {
            showToast("Подписка обновлена", .success)
        } else if !Task.isCancelled {
            showToast("Не удалось обновить подписку: " + (lastLoadError ?? "сервер не ответил"), .warning)
        }
        await loadAccount()
    }

    /// Pull to refresh: the same in a Task of its own. SwiftUI cancels the pull's own task when
    /// the screen changes under it (busy turns the refresh buttons), and the request with it,
    /// which then read as "Нет соединения с сервером подписки".
    func refreshFromPull() async {
        await Task { await refreshByHand() }.value
    }

    func showToast(_ text: String, _ kind: Toast.Kind = .info) {
        switch kind {
        case .success: Haptics.success()
        case .warning: Haptics.warning()
        case .info: break
        }
        toast = Toast(text: text, kind: kind)
        VoiceOver.announce(text)
    }

    func loadAccount() async {
        guard let url = subscription?.url else { return }
        let started = generation
        // Signed out or another key meanwhile: not the old key's account.
        if let info = await AccountAPI.fetch(url), started == generation { account = info }
    }

    /// Why the last load failed (for the note after Обновить подписку).
    private var lastLoadError: String?

    @discardableResult
    private func load(_ url: String, quiet: Bool = false) async -> Bool {
        let started = generation
        busy = true
        // Dropped: busy is the newer key's (or Выйти has reset it).
        defer { if started == generation { busy = false } }
        lastLoadError = nil
        do {
            let sub = try await SubscriptionAPI.fetch(url)
            // Signed out or another key since it began: this answer is for a key that's gone.
            guard started == generation else { return false }
            // Another key: the old one's account (devices, username) isn't this one's.
            if subscription?.keyToken != sub.keyToken { account = nil }
            subscription = sub
            Task { await Reminders.update(for: sub.info) }
            if let data = try? JSONEncoder().encode(sub) { UserDefaults.standard.set(data, forKey: subKey) }
            if !sub.servers.contains(where: { $0.id == selectedId }) { select(sub.servers.first?.id) }
            // While off: what iOS Settings and Не отключать VPN start follows it too (a ping's
            // pause reconnects with a fresh config anyway).
            if let vpn, !pingBusy, !vpnPaused {
                let picked = selected
                Task { await vpn.refreshProfile(sub.servers, picked: picked) }
            }
            return true
        } catch {
            guard started == generation else { return false }
            let text = error.localizedDescription
            lastLoadError = text.prefix(1).lowercased() + text.dropFirst()
            if !quiet || subscription == nil { message = text }
            return false
        }
    }

    func select(_ id: String?) {
        selectedId = id
        UserDefaults.standard.set(id, forKey: selKey)
    }

    /// Connected: the VPN may have come up without the app (iOS Settings, Не отключать VPN) on
    /// the profile's last server, not the one picked here. The pick follows what really runs.
    func followTunnel() async {
        let picked = selectedId
        guard let name = await TunnelSwitch.serverName(), picked == selectedId, vpn?.isConnected == true,
              name != selected?.name, let s = subscription?.servers.first(where: { $0.name == name }) else { return }
        select(s.id)
    }

    /// Выйти and Удалить аккаунт: what the key left on this device goes (the key, its account,
    /// favourites, pings, reminders, the cabinet's ID). The VPN and its profile:
    /// VPNController.removeProfile. The app's own settings stay.
    func logout() {
        // A fetch of this key still under way is dropped when it answers.
        generation += 1
        busy = false
        // A ping's pause doesn't bring the VPN back behind the welcome screen.
        stopPing()
        Reminders.reset()
        paywall = nil
        subscription = nil
        account = nil
        pings = [:]
        pingErrors = [:]
        favorites = []
        select(nil)
        for key in [subKey, favKey, clientIdKey] { UserDefaults.standard.removeObject(forKey: key) }
    }

    /// Servers being measured right now (spinners).
    @Published var measuring: Set<String> = []
    /// Those of them measured by their own ping button (a ping-all ending leaves them be).
    private var oneMeasuring: Set<String> = []
    private var oneTasks: [String: Task<Void, Never>] = [:]
    /// Ping-all results that came while the server's own ping ran (shown if that is stopped),
    /// and the servers ping-all hasn't reported yet.
    private var droppedAll: [String: (Int, String?)] = [:]
    private var allPending: Set<String> = []
    private var pingTask: Task<Void, Never>?

    /// Every server through Xray, the tab on screen first, all in one batch where they fit;
    /// pressed again while measuring: stops (old values stay).
    func pingAll(first: [String] = []) {
        if pinging { stopPing(); return }
        guard let servers = subscription?.servers else { return }
        // [first] in its own order (as shown: favourites first), then the rest.
        let byId = Dictionary(servers.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let order = first.compactMap { byId[$0] } + servers.filter { !first.contains($0.id) }
        pinging = true
        pingTotal = order.count
        measuring = Set(order.map(\.id)).union(oneMeasuring)
        allPending = Set(order.map(\.id))
        droppedAll = [:]
        pingTask = Task {
            // Стоп before the VPN was touched.
            guard !Task.isCancelled else { return }
            let hold = await beginPing()
            defer { endPing(hold) }
            // Стоп while the VPN was going off.
            guard !Task.isCancelled else { return }
            pingAllHold = hold
            await XrayPing.pingMany(order) { [weak self] id, ms, error in
                // A stopped run's call may still answer: its results don't go into a newer one.
                guard let self, self.pinging, self.pingAllHold == hold else { return }
                self.allPending.remove(id)
                // Its own ping is running: that one's result wins; this one waits in case it's stopped.
                if self.oneMeasuring.contains(id) { self.droppedAll[id] = (ms, error); return }
                self.pings[id] = ms
                self.pingErrors[id] = error
                self.measuring.remove(id)
            }
            if !Task.isCancelled { measuring = oneMeasuring; pinging = false; allPending = [] }
        }
    }

    /// How much of the running "Пинг" is done, 0…1.
    var pingProgress: Double {
        pingTotal > 0 ? Double(pingTotal - measuring.count) / Double(pingTotal) : 0
    }

    func stopPing() {
        pingTask?.cancel()
        pingTask = nil
        measuring = []
        pinging = false
        oneMeasuring = []
        oneTasks.values.forEach { $0.cancel() }
        oneTasks = [:]
        droppedAll = [:]
        allPending = []
        // The VPN and the switch come back now, not when libXray's call returns.
        let stopped = [pingAllHold].compactMap { $0 } + Array(oneHolds.values)
        pingAllHold = nil
        oneHolds = [:]
        for hold in stopped { endPing(hold) }
    }

    /// One server; pressed again while it is measured: stops it.
    func pingOne(_ server: Server) {
        if oneMeasuring.contains(server.id) {
            oneMeasuring.remove(server.id)
            oneTasks.removeValue(forKey: server.id)?.cancel()
            if let dropped = droppedAll.removeValue(forKey: server.id) {
                // Ping-all measured it meanwhile: that result after all.
                pings[server.id] = dropped.0
                pingErrors[server.id] = dropped.1
                measuring.remove(server.id)
            } else if !(pinging && allPending.contains(server.id)) {
                measuring.remove(server.id)
            }
            if let hold = oneHolds.removeValue(forKey: server.id) { endPing(hold) }
            return
        }
        oneMeasuring.insert(server.id)
        measuring.insert(server.id)
        oneTasks[server.id] = Task {
            let hold = await beginPing()
            defer { endPing(hold) }
            // Stopped while the VPN was going off.
            guard oneMeasuring.contains(server.id) else { return }
            oneHolds[server.id] = hold
            await XrayPing.pingMany([server]) { [weak self] id, ms, error in
                guard let self, self.oneHolds[id] == hold, self.oneMeasuring.contains(id) else { return }
                self.pings[id] = ms
                self.pingErrors[id] = error
                self.oneMeasuring.remove(id)
                self.droppedAll[id] = nil
                self.measuring.remove(id)
            }
        }
    }

    /// Other checks that must not go through the tunnel (Технический режим's TCP check):
    /// the VPN off for their time, as for a ping, and back on after.
    func withVPNPaused(_ work: () async -> Void) async {
        let hold = await beginPing()
        defer { endPing(hold) }
        // The app left while the VPN was going off: not run, the VPN comes back.
        guard UIApplication.shared.applicationState != .background else { return }
        checkHolds.insert(hold)
        await work()
    }

    /// The app left: pings stop and checks give their holds back, so the VPN comes back now, in
    /// the background time pauseTime holds, not after them (a check's rest goes through the VPN).
    /// A pause still under way brings it back as soon as it is done (its task's defer).
    func leftApp() {
        if pingBusy { stopPing() }
        let checks = checkHolds
        checkHolds = []
        for hold in checks { endPing(hold) }
    }

    /// The VPN coming back after a ping: that reconnect goes over to a server picked meanwhile
    /// (a switchTo of its own would run alongside it). Read on a tap only, so not published.
    var resumingAfterPing: Bool { resuming != nil }

    /// The first ping in flight stops the VPN (remembering its server); later ones wait for that.
    /// A reconnect after the previous ping still under way is let finish first, then paused.
    private func beginPing() async -> UUID {
        let hold = UUID()
        holds.insert(hold)
        pingBusy = true
        if holds.count == 1, let vpn, vpn.isOn || resuming != nil {
            let previous = resuming
            let server = selected
            offsAtPause = vpn.offsByHand
            vpnPaused = true
            pausing = Task {
                await previous?.value
                if await vpn.pauseForPing() { self.resumeOn = server } else { self.vpnPaused = false }
            }
        }
        await pausing?.value
        return hold
    }

    /// The last hold given back: the VPN back on the server it was on (unless it was turned on
    /// or off by hand meanwhile). A hold already given back by Стоп is ignored.
    private func endPing(_ hold: UUID) {
        guard holds.remove(hold) != nil else { return }
        if pingAllHold == hold { pingAllHold = nil }
        if let key = oneHolds.first(where: { $0.value == hold })?.key { oneHolds[key] = nil }
        checkHolds.remove(hold)
        guard holds.isEmpty else { return }
        pingBusy = false
        pausing = nil
        guard let server = resumeOn else { vpnPaused = false; return }
        resumeOn = nil
        guard let vpn, !vpn.isOn else { vpnPaused = false; return }
        // Turned off by hand during the pause (Siri, ⏻, Выйти): stays off, and the activity
        // left saying "Переключаемся…" goes. The pause may have ended after the off, so not
        // resumeOn alone.
        guard vpn.offsByHand == offsAtPause else {
            vpnPaused = false
            LiveActivityController.sync(vpn.state, server: selected?.name, paused: false)
            return
        }
        // "Пауза" stays on screen until connect has taken over (Подключаемся…).
        resuming = Task {
            var target = selected ?? server
            await vpn.connect(target)
            // Picked meanwhile (the pick leaves the switch to this): over to it, again for each
            // newer pick. Not when a newer ping waits to pause it: its own resume takes the pick.
            while holds.isEmpty, let now = selected, now.id != target.id, vpn.isOn {
                target = now
                await vpn.switchTo(now)
            }
            resuming = nil
            // A newer ping may have paused it again meanwhile: that one owns the flag now.
            if holds.isEmpty { vpnPaused = false }
        }
    }

    // MARK: Проверить соединение

    private var checkTask: Task<Void, Never>?

    /// A request through the VPN that is on now. auto: right after connecting, a few tries
    /// (the tunnel may need a moment), no haptics; by hand: one more try on a failure.
    func checkConnection(auto: Bool = false) {
        guard let vpn, vpn.isConnected else { return }
        checkTask?.cancel()
        check = .checking
        checkTask = Task {
            if auto { try? await Task.sleep(nanoseconds: 1_200_000_000) }
            var result = ConnectionCheck.Status.failed("")
            for attempt in 0..<(auto ? 3 : 2) {
                if Task.isCancelled { return }
                if attempt > 0 { try? await Task.sleep(nanoseconds: 1_500_000_000) }
                if Task.isCancelled { return }
                result = await ConnectionCheck.run()
                if case .ok = result { break }
            }
            guard !Task.isCancelled else { return }
            check = result
            if !auto {
                if case .ok = result { Haptics.success() } else { Haptics.warning() }
            }
        }
    }

    /// The VPN went off or to another server: the old result says nothing any more.
    func resetCheck() {
        checkTask?.cancel()
        checkTask = nil
        check = .idle
    }

    nonisolated static func tcpPing(host: String?, port: Int?) async -> Int {
        guard let host, let port, let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return -1 }
        return await withCheckedContinuation { cont in
            let conn = NWConnection(host: NWEndpoint.Host(host), port: p, using: .tcp)
            let start = Date()
            var done = false
            let lock = NSLock()
            func finish(_ v: Int) {
                lock.lock(); defer { lock.unlock() }
                if done { return }
                done = true
                conn.cancel()
                cont.resume(returning: v)
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(Int(Date().timeIntervalSince(start) * 1000))
                case .failed, .cancelled: finish(-1)
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + 4) { finish(-1) }
        }
    }
}
