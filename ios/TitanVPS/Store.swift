import Foundation
import Network
import SwiftUI
import UserNotifications

@MainActor
final class AppStore: ObservableObject {
    @Published var subscription: Subscription? {
        didSet {
            indexServers()
            // Выйти (logout): the Live Activity goes with the key.
            if subscription == nil, oldValue != nil { LiveActivityController.signedOut() }
        }
    }
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
    #if TITAN_PAYMENTS
    /// Тарифы (PaywallView) on screen and why it opened; RootView shows it.
    @Published var paywall: PaywallReason?
    /// Bumped to close the tabs' own sheets (Личный кабинет, Устройства, О приложении): Тарифы
    /// from a reminder then opens over the app, not under one of them (RootView).
    @Published var closeSheets = 0
    #endif

    /// The panel's switches in force (RemoteConfig, docs/BACKEND-CONFIG.md): the saved ones from
    /// launch, newer ones with each load of the pass (updateRemote). Code off the main thread reads
    /// RemoteConfig.current, set at the same moment.
    @Published private(set) var remote = RemoteConfig.current
    /// The config's URL being asked: one request at a time.
    private var remoteFetch: Task<Void, Never>?

    /// Favourite servers, by name (ids change with the subscription).
    @Published private(set) var favorites: Set<String> = []
    /// On Wi-Fi: bypass servers (Обходы) work only over mobile internet.
    @Published private(set) var onWifi = false
    /// How the direction is picked (RouteChoice): «Авто», «Авто · объезды» or by hand. Saved; the
    /// picked server itself is still selectedId (the Live Activity, Siri and Пункт управления go
    /// by it). Read it through `choice`.
    @Published private(set) var routeChoice: RouteChoice = .auto
    /// «Проверить маршрут» failed this many times in a row on this trip (HomeView offers «Поехать
    /// объездом» after two on «Авто»).
    @Published private(set) var checkFailures = 0
    /// On mobile internet with nothing in between (no VPN, ours or another's): where
    /// MobileNetCheck can ask directly.
    private var cellularDirect = false
    private var netCheck: Task<Void, Never>?
    private var netCheckedAt: Date?
    /// The last MobileNetCheck and when (Технический режим).
    private(set) var mobileNet: (verdict: MobileNetCheck.Verdict, at: Date)?
    /// «Авто» picked over the «Авто · объезды» the check switched to: not switched again until
    /// mobile internet opens or Wi-Fi comes. Saved: the check at the next launch doesn't switch
    /// it back either.
    private var detectionDeclined = UserDefaults.standard.bool(forKey: RouteChoice.declinedKey) {
        didSet {
            if detectionDeclined != oldValue { UserDefaults.standard.set(detectionDeclined, forKey: RouteChoice.declinedKey) }
        }
    }
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
        routeChoice = UserDefaults.standard.string(forKey: RouteChoice.key).flatMap(RouteChoice.init(rawValue:)) ?? .auto
        // The pick written without the app's store (the Live Activity's ‹ › with the app not
        // running), or by a build before the choice: by hand, unless it is «Авто»'s own.
        if let id = selectedId, UserDefaults.standard.string(forKey: RouteChoice.pickKey) != id {
            routeChoice = id == autoServer?.id ? .auto : .manual
            UserDefaults.standard.set(routeChoice.rawValue, forKey: RouteChoice.key)
            UserDefaults.standard.set(id, forKey: RouteChoice.pickKey)
        }
        favorites = Set(UserDefaults.standard.stringArray(forKey: favKey) ?? [])
        // Технический режим turned off in the saved config: left. A choice it doesn't allow is
        // settled at the first load (fitChoice), once it is known whether the route is up.
        if !remote.techModeOn { UserDefaults.standard.set(false, forKey: TechMode.key) }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // With the VPN on the path is the tunnel; the Wi-Fi interface is still listed.
            let wifi = path.status == .satisfied && path.availableInterfaces.contains { $0.type == .wifi }
            // Mobile internet itself, not a tunnel over it.
            let cellular = path.status == .satisfied && !wifi && path.usesInterfaceType(.cellular)
            Task { @MainActor in self?.pathChanged(wifi: wifi, cellular: cellular) }
        }
        pathMonitor.start(queue: DispatchQueue(label: "titan.path"))
        UNUserNotificationCenter.current().delegate = NotificationPresenter.shared
        Self.current = self
    }

    /// Support: the panel's config (support.url), else the subscription's support-url, else the bot.
    var supportURL: URL {
        remote.supportURL
            ?? subscription?.info.supportUrl.flatMap { URL(string: $0) }.flatMap { $0.scheme == nil ? nil : $0 }
            ?? Config.telegram
    }

    #if TITAN_PAYMENTS
    /// The cabinet showed «Ваш ID»: kept for this key, it names the account to support.
    func rememberClientId(_ id: String) {
        guard let key = subscription?.keyHash, clientId != id else { return }
        UserDefaults.standard.set(["key": key, "id": id], forKey: clientIdKey)
    }
    #endif

    /// «Ваш ID» from the cabinet, if it was shown for this key (a build with it, TITAN_PAYMENTS).
    var clientId: String? {
        guard let saved = UserDefaults.standard.dictionary(forKey: clientIdKey) as? [String: String],
              let key = subscription?.keyHash, saved["key"] == key else { return nil }
        return saved["id"]?.nilIfEmpty
    }

    /// Удалить аккаунт: the request copied for support. The account by the cabinet's ID, else
    /// by the bot's username, else by the key's short hash (docs/BACKEND-APPSTORE.md).
    var deletionRequest: String {
        let id = clientId ?? account?.username ?? subscription.map { "\($0.keyHash) (хеш кода проездного)" } ?? "—"
        return "Прошу удалить мой аккаунт Titan. ID: \(id)"
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

    // MARK: «Авто» and «Авто · объезды»

    /// What «Авто» goes to now (RouteChoice.autoServer; which row first, the panel's config).
    var autoServer: Server? {
        RouteChoice.autoServer(subscription?.servers ?? [], skip: unsupported, prefer: remote.autoPick)
    }

    /// What «Авто · объезды» goes to now (RouteChoice.bypassServer, by the list's last check). None
    /// with it turned off in the panel's config (routes.autoBypass): no card, no check, no offer.
    var bypassServer: Server? {
        guard remote.autoBypassOn else { return nil }
        return RouteChoice.bypassServer(subscription?.servers ?? [], pings: pings, skip: unsupported)
    }

    /// The choice as it stands. Its direction may lag behind what it would pick now (the pass
    /// refreshed on the way): «Авто» stays picked, the pick follows once stopped (load).
    var choice: RouteChoice { routeChoice }

    /// «Авто» or «Авто · объезды» (.manual: the pick as it is): saved, and the pick moved to the
    /// direction it stands for now. Returns that direction; reconnecting on the way is the
    /// caller's (HomeView, as a pick in the list). byUser: tapped («Авто» over the check's
    /// объезды holds it off until mobile internet opens).
    @discardableResult
    func choose(_ c: RouteChoice, byUser: Bool = false) -> Server? {
        if byUser, c == .auto, routeChoice == .bypassDetected { detectionDeclined = true }
        setChoice(c)
        let to: Server?
        switch c {
        case .auto: to = autoServer
        case .bypass, .bypassDetected: to = bypassServer
        case .manual: to = selected
        }
        if let to, to.id != selectedId { setPick(to.id) }
        return to
    }

    private func setChoice(_ c: RouteChoice) {
        guard routeChoice != c else { return }
        routeChoice = c
        UserDefaults.standard.set(c.rawValue, forKey: RouteChoice.key)
    }

    /// The pick itself, the choice left as it is. Also noted as the choice's own (pickKey): a
    /// pick written elsewhere is told apart at the next launch.
    private func setPick(_ id: String?) {
        selectedId = id
        UserDefaults.standard.set(id, forKey: selKey)
        UserDefaults.standard.set(id, forKey: RouteChoice.pickKey)
    }

    /// The network changed: Wi-Fi or not, and mobile internet worth a look (MobileNetCheck).
    private func pathChanged(wifi: Bool, cellular: Bool) {
        if onWifi != wifi { onWifi = wifi }
        cellularDirect = cellular
        if wifi {
            detectionDeclined = false
            leaveBypassOnWifi()
        } else if cellular {
            checkMobileNet()
        }
    }

    /// Wi-Fi came with «Авто · объезды» picked: «Авто» instead (объезды run on mobile internet
    /// only), and over to it on the way. A bypass picked in the list moves as before (HomeView).
    private func leaveBypassOnWifi() {
        guard choice.isBypass, bypassLockedOnWifi else { return }
        let from = selectedId
        guard let to = choose(.auto), to.id != from, let vpn, vpn.isOn else { return }
        // Coming back after a ping: that reconnect goes over to the pick itself.
        if !resumingAfterPing { Task { await vpn.switchTo(to) } }
        message = "Объезды работают только через мобильный интернет. Перестроили маршрут на «\(RouteName.shown(to.name.splitFlag.1))»."
    }

    /// The route is stopped, and nothing is about to bring it up (a ping's pause, a connect).
    private func routeStopped(_ vpn: VPNController) -> Bool {
        !vpn.isOn && vpn.state != .disconnecting && !vpn.connectsUnderWay && !pingBusy && !vpnPaused
    }

    /// Mobile internet restricted («если нету инета»)? Asked directly (MobileNetCheck) as the app
    /// comes to the front and when the network changes, once a minute at most, only on mobile
    /// internet with the route stopped (its requests would go over the route otherwise) and only
    /// where the choice is the app's to change: «Авто» goes over to «Авто · объезды» (with a note
    /// on its card) when only the whitelisted sites open; the check's own «Авто · объезды» back
    /// to «Авто» when all of them open again. A pick by hand or «Авто · объезды» tapped stay.
    func checkMobileNet() {
        let c = choice
        guard netCheck == nil, let vpn, cellularDirect, !onWifi, subscription != nil, remote.detectionOn,
              c == .bypassDetected || (c == .auto && !detectionDeclined && bypassServer != nil),
              UserDefaults.standard.bool(forKey: PrivacyNoticeView.key),
              UIApplication.shared.applicationState != .background,
              netCheckedAt.map({ Date().timeIntervalSince($0) >= 60 }) ?? true else { return }
        netCheck = Task {
            defer { netCheck = nil }
            // At launch the VPN's state is the profile's only once read; iOS may have it up.
            await vpn.loaded()
            guard routeStopped(vpn), !(await TunnelSwitch.isOn()), routeStopped(vpn), cellularDirect else { return }
            netCheckedAt = Date()
            let verdict = await MobileNetCheck.run()
            mobileNet = (verdict, Date())
            if verdict == .open { detectionDeclined = false }
            // Still stopped on mobile internet: the answers came straight from it (and the
            // check still wanted: the config may have come meanwhile).
            guard routeStopped(vpn), cellularDirect, !onWifi, subscription != nil, remote.detectionOn else { return }
            switch (verdict, choice) {
            case (.restricted, .auto) where !detectionDeclined:
                guard let to = bypassServer, !blockedOnWifi(to) else { return }
                setChoice(.bypassDetected)
                if to.id != selectedId { setPick(to.id) }
            case (.open, .bypassDetected):
                choose(.auto)
            default:
                break
            }
        }
    }

    /// Автоподключение waits for this (RootView): the mobile internet check, started if it is due,
    /// and its verdict in, so on restricted mobile internet «Авто» starts on «Авто · объезды».
    /// A few seconds at most (MobileNetCheck's timeouts); nothing to wait for where checkMobileNet
    /// doesn't run (Wi-Fi, a check under a minute ago, a pick by hand).
    func settleMobileNet() async {
        checkMobileNet()
        await netCheck?.value
    }

    // MARK: The panel's config (RemoteConfig)

    /// After a load of the pass: the config its headers bring. «titan-config-url» wins (its JSON,
    /// asked with the last ETag), else «titan-config»; with neither, or a URL that didn't answer
    /// with a config, the one in force stays.
    private func updateRemote(from info: SubscriptionInfo) {
        if let url = RemoteConfig.https(info.remoteConfigUrl) {
            fetchRemote(url)
        } else if let header = info.remoteConfig, let config = RemoteConfig.parse(header: header) {
            applyRemote(config, source: RemoteConfig.Source(kind: "header", at: Date()))
        }
    }

    /// Back in the app: the config's URL again once a quarter of an hour has passed since its
    /// last answer (the pass itself comes again after half an hour, RootView). Not before
    /// Конфиденциальность is accepted.
    func refreshRemoteIfStale() {
        guard UserDefaults.standard.bool(forKey: PrivacyNoticeView.key), subscription != nil,
              let source = RemoteConfig.source, source.kind == "url", let url = RemoteConfig.https(source.url),
              Date().timeIntervalSince(source.at) >= 900 else { return }
        fetchRemote(url)
    }

    private func fetchRemote(_ url: URL) {
        guard remoteFetch == nil else { return }
        let saved = RemoteConfig.source
        // The ETag is only the same URL's.
        let etag = saved?.url == url.absoluteString ? saved?.etag : nil
        remoteFetch = Task {
            defer { remoteFetch = nil }
            switch await RemoteConfigAPI.fetch(url, etag: etag) {
            case .config(let config, etag: let tag):
                applyRemote(config, source: RemoteConfig.Source(kind: "url", url: url.absoluteString, etag: tag, at: Date()))
            case .unchanged:
                RemoteConfigCache.noteSource(RemoteConfig.Source(kind: "url", url: url.absoluteString, etag: etag, at: Date()))
            case .failed:
                break
            }
        }
    }

    /// Saved, and in force at once: the screens follow `remote`; what isn't on a screen is set here.
    private func applyRemote(_ config: RemoteConfig, source: RemoteConfig.Source) {
        let old = remote
        RemoteConfigCache.save(config, source: source)
        guard config != old else { return }
        remote = config
        let stopped = vpn.map { routeStopped($0) } ?? false
        fitChoice(stopped: stopped)
        // «Авто» to the row it means now, while stopped (on the way the route keeps its direction).
        if old.autoPick != config.autoPick, choice == .auto, stopped { choose(.auto) }
        // Технический режим turned off: left too (bypass servers on Wi-Fi again locked).
        if !config.techModeOn { UserDefaults.standard.set(false, forKey: TechMode.key) }
        // The Live Activity turned off ends; turned on, it starts (LiveActivityController.enabled).
        if old.liveActivityOn != config.liveActivityOn, let vpn {
            LiveActivityController.sync(vpn.state, server: selected?.name, paused: vpnPaused)
        }
        #if TITAN_PAYMENTS
        // An endpoint for the first time: the purchases kept for it go now, not at the next launch.
        if old.purchaseEndpoint == nil, config.purchaseEndpoint != nil {
            Task { await AppStorePurchases.shared.deliverUnfinished() }
        }
        #endif
    }

    /// The choice as the config allows it: «Авто · объезды» turned off (routes.autoBypass) goes to
    /// «Авто» while stopped, to the same direction picked by hand on the way; the check's own
    /// «Авто · объезды» with the check turned off (routes.restrictedDetection), which would never
    /// go back by itself, to «Авто» while stopped, or stays as if tapped.
    private func fitChoice(stopped: Bool) {
        if routeChoice.isBypass && !remote.autoBypassOn {
            if stopped { choose(.auto) } else { setChoice(.manual) }
        } else if routeChoice == .bypassDetected && !remote.detectionOn {
            if stopped { choose(.auto) } else { setChoice(.bypass) }
        }
    }

    /// Theme, favourites, Автоподключение, Не отключать VPN and the picked server back to the
    /// start; the key stays.
    func resetSettings() {
        favorites = []
        UserDefaults.standard.removeObject(forKey: favKey)
        UserDefaults.standard.set("system", forKey: "theme")
        UserDefaults.standard.set(false, forKey: "autoconnect")
        UserDefaults.standard.set(true, forKey: VPNController.keepAliveKey)
        // «Авто» again, the list tucked away.
        detectionDeclined = false
        choose(.auto)
        UserDefaults.standard.removeObject(forKey: RouteChoice.listOpenKey)
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
            message = "Это не код проездного Titan"
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
            showToast("Проездной обновлён", .success)
        } else if !Task.isCancelled {
            showToast("Не удалось обновить проездной: " + (lastLoadError ?? "нет ответа"), .warning)
        }
        await loadAccount()
    }

    /// Pull to refresh: the same in a Task of its own. SwiftUI cancels the pull's own task when
    /// the screen changes under it (busy turns the refresh buttons), and the request with it,
    /// which then read as "Нет соединения с сервером подписки".
    func refreshFromPull() async {
        await Task { await refreshByHand() }.value
    }

    /// haptic: false where the control that refused has its own (the Выкл | Вкл knob's shake).
    func showToast(_ text: String, _ kind: Toast.Kind = .info, haptic: Bool = true) {
        switch kind {
        case .success where haptic: Haptics.success()
        case .warning where haptic: Haptics.warning()
        default: break
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
            // The panel's config first: an inline one is in force before the pick is settled
            // below, and the choice as the config in force allows it.
            updateRemote(from: sub.info)
            fitChoice(stopped: vpn.map { routeStopped($0) } ?? false)
            Task { await Reminders.update(for: sub.info) }
            if let data = try? JSONEncoder().encode(sub) { UserDefaults.standard.set(data, forKey: subKey) }
            if selectedId == nil || !sub.servers.contains(where: { $0.id == selectedId }) {
                // The pick gone from the pass (or none yet): the choice's own direction again,
                // «Авто» for one picked by hand; the first row where it has none.
                if choose(routeChoice == .manual ? .auto : routeChoice) == nil { setPick(sub.servers.first?.id) }
                leaveBypassOnWifi()
            } else if let vpn, !vpn.isOn, !vpn.connectsUnderWay, !pingBusy, !vpnPaused, choice != .manual {
                // Stopped: «Авто» and «Авто · объезды» follow the pass as it is now (a new АВТО
                // row, a bypass gone). On the way the route keeps its direction.
                choose(choice)
            }
            // While off: what iOS Settings and Не отключать VPN start follows it too (a ping's
            // pause reconnects with a fresh config anyway). While on with Не отключать VPN: the
            // profile, for the next start by iOS (on demand after a kill); the tunnel up keeps its
            // own. Each does nothing in the other case (or with a connect under way).
            if let vpn, !pingBusy, !vpnPaused {
                let picked = selected
                Task { await vpn.refreshProfile(sub.servers, picked: picked) }
                if VPNController.keepAlive { vpn.setKeepAlive(true, rebuilding: sub.servers) }
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

    /// A direction picked (the list, the Live Activity's ‹ ›, Siri): by hand from now on, or
    /// «Авто» when it is «Авто»'s own direction (RouteChoice).
    func select(_ id: String?) {
        setPick(id)
        guard let id else { return }
        setChoice(id == autoServer?.id ? .auto : .manual)
    }

    /// Connected: the VPN may have come up without the app (iOS Settings, Не отключать VPN) on
    /// the profile's last server, not the one picked here. The pick follows what really runs
    /// («Авто · объезды» stays, on a bypass server).
    func followTunnel() async {
        let picked = selectedId
        guard let name = await TunnelSwitch.serverName(), picked == selectedId, vpn?.isConnected == true,
              name != selected?.name, let s = subscription?.servers.first(where: { $0.name == name }) else { return }
        if choice.isBypass && ServerGroup.isBypass(s) { setPick(s.id) } else { select(s.id) }
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
        #if TITAN_PAYMENTS
        paywall = nil
        #endif
        subscription = nil
        account = nil
        pings = [:]
        pingErrors = [:]
        favorites = []
        select(nil)
        // The next key starts on «Авто».
        routeChoice = .auto
        detectionDeclined = false
        netCheckedAt = nil
        mobileNet = nil
        for key in [subKey, favKey, clientIdKey, RouteChoice.key, RouteChoice.pickKey, RouteChoice.declinedKey] {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }

    /// Servers being measured right now (spinners).
    @Published var measuring: Set<String> = []
    /// Those of them measured by their own ping button (a ping-all ending leaves them be).
    private var oneMeasuring: Set<String> = []
    private var oneTasks: [String: Task<Void, Never>] = [:]
    /// Ping-all results that came while the server's own ping ran (shown if that is stopped),
    /// and the servers ping-all hasn't reported a final result for yet.
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
            await XrayPing.pingMany(order) { [weak self] id, ms, error, done in
                // A stopped run's call may still answer: its results don't go into a newer one.
                guard let self, self.pinging, self.pingAllHold == hold else { return }
                // A slow first answer (not done): shown now, its spinner on until its recheck.
                if done { self.allPending.remove(id) }
                // Its own ping is running: that one's result wins; this one waits in case it's stopped.
                if self.oneMeasuring.contains(id) { self.droppedAll[id] = (ms, error); return }
                self.pings[id] = ms
                self.pingErrors[id] = error
                if done { self.measuring.remove(id) }
            }
            if !Task.isCancelled {
                measuring = oneMeasuring
                pinging = false
                allPending = []
                // «Авто · объезды» goes by these: its best bypass now. Paused for the check, the
                // route comes back on it (endPing's resume takes the pick).
                if choice.isBypass, self.vpn?.isOn != true, let best = bypassServer, best.id != selectedId {
                    setPick(best.id)
                }
            }
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
                // Ping-all measured it meanwhile: that result after all (its first one, if its
                // recheck is still to come: the spinner stays for that).
                pings[server.id] = dropped.0
                pingErrors[server.id] = dropped.1
            }
            if !(pinging && allPending.contains(server.id)) { measuring.remove(server.id) }
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
            // One server: no recheck, every result final.
            await XrayPing.pingMany([server]) { [weak self] id, ms, error, _ in
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
            if case .ok = result { checkFailures = 0 } else { checkFailures += 1 }
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
        checkFailures = 0
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
