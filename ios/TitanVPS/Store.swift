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
    /// The VPN was turned off for a ping and comes back by itself after it.
    @Published private(set) var vpnPaused = false
    /// Servers in the running "Пинг" (for its progress ring).
    @Published private(set) var pingTotal = 0

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
    private var pausing: Task<Void, Never>?
    /// The reconnect after the last ping; a new ping waits for it before pausing again.
    private var resuming: Task<Void, Never>?
    private var resumeOn: Server?

    private let subKey = "titan.subscription"
    private let selKey = "titan.selected"
    private let favKey = "titan.favorites"
    private let pathMonitor = NWPathMonitor()

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

    /// Theme, favourites and the picked server back to the start; the key stays.
    func resetSettings() {
        favorites = []
        UserDefaults.standard.removeObject(forKey: favKey)
        UserDefaults.standard.set("system", forKey: "theme")
        UserDefaults.standard.set(false, forKey: "autoconnect")
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
        guard await load(url) else { return false }
        await loadAccount()
        return true
    }

    /// quiet: at launch / in the background with a saved subscription: no alert on failure
    /// (the network may be busy with the VPN coming up), one more try a bit later.
    @discardableResult
    func refresh(quiet: Bool = false) async -> Bool {
        guard let url = subscription?.url else { return false }
        var ok = await load(url, quiet: quiet)
        if !ok && quiet {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            ok = await load(url, quiet: true)
        }
        await loadAccount()
        return ok
    }

    /// Обновить подписку by hand: says how it went in a note at the top (no alert to dismiss).
    func refreshByHand() async {
        guard !busy, let url = subscription?.url else { return }
        if await load(url, quiet: true) {
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
        // VoiceOver says it too (a moment later, so the tap's own speech doesn't cut it off).
        if UIAccessibility.isVoiceOverRunning {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                UIAccessibility.post(notification: .announcement, argument: text)
            }
        }
    }

    func loadAccount() async {
        guard let url = subscription?.url else { return }
        if let info = await AccountAPI.fetch(url) { account = info }
    }

    /// Why the last load failed (for the note after Обновить подписку).
    private var lastLoadError: String?

    @discardableResult
    private func load(_ url: String, quiet: Bool = false) async -> Bool {
        busy = true
        defer { busy = false }
        lastLoadError = nil
        do {
            let sub = try await SubscriptionAPI.fetch(url)
            subscription = sub
            Task { await Reminders.update(for: sub.info) }
            if let data = try? JSONEncoder().encode(sub) { UserDefaults.standard.set(data, forKey: subKey) }
            if !sub.servers.contains(where: { $0.id == selectedId }) { select(sub.servers.first?.id) }
            // While off: what iOS Settings and Автоподключение start follows it too (a ping's
            // pause reconnects with a fresh config anyway).
            if let vpn, !pingBusy, !vpnPaused {
                let picked = selected
                Task { await vpn.refreshProfile(sub.servers, picked: picked) }
            }
            return true
        } catch {
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

    /// Connected: the VPN may have come up without the app (iOS Settings, Автоподключение) on
    /// the profile's last server, not the one picked here. The pick follows what really runs.
    func followTunnel() async {
        let picked = selectedId
        guard let name = await TunnelSwitch.serverName(), picked == selectedId, vpn?.isConnected == true,
              name != selected?.name, let s = subscription?.servers.first(where: { $0.name == name }) else { return }
        select(s.id)
    }

    func logout() {
        Reminders.reset()
        subscription = nil
        account = nil
        pings = [:]
        pingErrors = [:]
        select(nil)
        UserDefaults.standard.removeObject(forKey: subKey)
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
        await work()
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
    /// by hand meanwhile). A hold already given back by Стоп is ignored.
    private func endPing(_ hold: UUID) {
        guard holds.remove(hold) != nil else { return }
        if pingAllHold == hold { pingAllHold = nil }
        if let key = oneHolds.first(where: { $0.value == hold })?.key { oneHolds[key] = nil }
        guard holds.isEmpty else { return }
        pingBusy = false
        pausing = nil
        guard let server = resumeOn else { vpnPaused = false; return }
        resumeOn = nil
        guard let vpn, !vpn.isOn else { vpnPaused = false; return }
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
