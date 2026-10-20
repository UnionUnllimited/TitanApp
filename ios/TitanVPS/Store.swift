import Foundation
import Network
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    @Published var subscription: Subscription?
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

    /// Set by the app: pings turn the VPN off while they run and back on after.
    weak var vpn: VPNController?
    private var pingUsers = 0
    private var pausing: Task<Void, Never>?
    private var resumeOn: Server?

    private let subKey = "titan.subscription"
    private let selKey = "titan.selected"
    private let favKey = "titan.favorites"
    private let pathMonitor = NWPathMonitor()

    init() {
        if let data = UserDefaults.standard.data(forKey: subKey),
           let sub = try? JSONDecoder().decode(Subscription.self, from: data) {
            subscription = sub
        }
        selectedId = UserDefaults.standard.string(forKey: selKey)
        favorites = Set(UserDefaults.standard.stringArray(forKey: favKey) ?? [])
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // With the VPN on the path is the tunnel; the Wi-Fi interface is still listed.
            let wifi = path.status == .satisfied && path.availableInterfaces.contains { $0.type == .wifi }
            Task { @MainActor in if self?.onWifi != wifi { self?.onWifi = wifi } }
        }
        pathMonitor.start(queue: DispatchQueue(label: "titan.path"))
    }

    /// Support: the subscription's support-url, else the bot.
    var supportURL: URL {
        subscription?.info.supportUrl.flatMap { URL(string: $0) }.flatMap { $0.scheme == nil ? nil : $0 } ?? Config.telegram
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
            if let data = try? JSONEncoder().encode(sub) { UserDefaults.standard.set(data, forKey: subKey) }
            if !sub.servers.contains(where: { $0.id == selectedId }) { select(sub.servers.first?.id) }
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

    func logout() {
        subscription = nil
        account = nil
        pings = [:]
        pingErrors = [:]
        select(nil)
        UserDefaults.standard.removeObject(forKey: subKey)
    }

    /// Servers being measured right now (spinners).
    @Published var measuring: Set<String> = []
    private var pingTask: Task<Void, Never>?

    /// Every server through Xray, the tab on screen first, all in one batch where they fit;
    /// pressed again while measuring: stops (old values stay).
    func pingAll(first: [String] = []) {
        if pinging { stopPing(); return }
        guard let servers = subscription?.servers else { return }
        let order = servers.filter { first.contains($0.id) } + servers.filter { !first.contains($0.id) }
        pinging = true
        pingTotal = order.count
        measuring = Set(order.map(\.id))
        pingTask = Task {
            await beginPing()
            defer { endPing() }
            await XrayPing.pingMany(order) { [weak self] id, ms, error in
                guard let self, self.pinging else { return }
                self.pings[id] = ms
                self.pingErrors[id] = error
                self.measuring.remove(id)
            }
            if !Task.isCancelled { measuring = []; pinging = false }
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
    }

    /// One server; pressed again while it is measured: stops it.
    func pingOne(_ server: Server) {
        if measuring.contains(server.id) { measuring.remove(server.id); return }
        measuring.insert(server.id)
        Task {
            await beginPing()
            defer { endPing() }
            await XrayPing.pingMany([server]) { [weak self] id, ms, error in
                guard let self, self.measuring.contains(id) else { return }
                self.pings[id] = ms
                self.pingErrors[id] = error
                self.measuring.remove(id)
            }
        }
    }

    /// Other checks that must not go through the tunnel (Технический режим's TCP check):
    /// the VPN off for their time, as for a ping, and back on after.
    func withVPNPaused(_ work: () async -> Void) async {
        await beginPing()
        defer { endPing() }
        await work()
    }

    /// The first ping in flight stops the VPN (remembering its server); later ones wait for that.
    private func beginPing() async {
        pingUsers += 1
        pingBusy = true
        if pingUsers == 1, let vpn, vpn.isOn {
            let server = selected
            vpnPaused = true
            pausing = Task {
                if await vpn.pauseForPing() { self.resumeOn = server } else { self.vpnPaused = false }
            }
        }
        await pausing?.value
    }

    /// The last ping done: the VPN back on the server it was on (unless it was turned on by hand meanwhile).
    private func endPing() {
        pingUsers -= 1
        guard pingUsers == 0 else { return }
        pingBusy = false
        pausing = nil
        guard let server = resumeOn else { vpnPaused = false; return }
        resumeOn = nil
        guard let vpn, !vpn.isOn else { vpnPaused = false; return }
        // "Пауза" stays on screen until connect has taken over (Подключаемся…).
        Task {
            await vpn.connect(selected ?? server)
            vpnPaused = false
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
