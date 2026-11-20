import Foundation

/// The owner's switches from the backend panel («включать и отключать многое», docs/BACKEND-CONFIG.md).
/// Every field is optional and nothing sent means today's behaviour, so an empty or missing config
/// changes nothing. It comes with the subscription, in its response headers: «titan-config» (the
/// JSON in base64) or «titan-config-url» (an https address to GET it from, the URL winning); with
/// neither the cached one stays. Kept in UserDefaults and in force from launch, without the
/// network (AppStore.remote, RemoteConfig.current off the main thread). Not secret: it opens
/// nothing, anyone with the pass can read it.
struct RemoteConfig: Codable, Equatable {
    var version: Int? = nil
    var payments: Payments? = nil
    var announce: Announce? = nil
    var maintenance: Maintenance? = nil
    var update: Update? = nil
    var support: Support? = nil
    var routes: Routes? = nil
    var roads: Roads? = nil
    var features: Features? = nil

    enum CodingKeys: String, CodingKey {
        case version, payments, announce, maintenance, update, support, routes, roads, features
    }

    /// Тарифы, App Store purchases and the cabinet (TITAN_PAYMENTS); each on unless sent false.
    struct Payments: Codable, Equatable {
        var enabled: Bool? = nil
        var appStore: Bool? = nil
        var otherMethods: Bool? = nil
        var cabinet: Bool? = nil
        /// Where App Store purchases go (docs/BACKEND-APPSTORE.md), https on one of our own hosts
        /// only: Config.purchaseEndpoint.
        var purchaseEndpoint: String? = nil

        enum CodingKeys: String, CodingKey {
            case enabled, appStore, otherMethods, cabinet, purchaseEndpoint
        }
    }

    /// A card on Маршрут: «info» on the brand's tint, «warning» on orange; a tap opens [url].
    struct Announce: Codable, Equatable {
        var id: String? = nil
        var text: String? = nil
        var level: String? = nil
        var url: String? = nil
        var dismissible: Bool? = nil

        enum CodingKeys: String, CodingKey {
            case id, text, level, url, dismissible
        }
    }

    /// Маршрут's maintenance line, as the account's own MAINTENANCE status shows it.
    struct Maintenance: Codable, Equatable {
        var enabled: Bool? = nil
        var text: String? = nil

        enum CodingKeys: String, CodingKey {
            case enabled, text
        }
    }

    /// Build numbers (CFBundleVersion, Профиль → Версия in brackets): under [minBuild] the app asks
    /// to update and does nothing else; under [latestBuild] a card says a newer one is out.
    struct Update: Codable, Equatable {
        var minBuild: Int64? = nil
        var latestBuild: Int64? = nil
        var url: String? = nil
        var text: String? = nil

        enum CodingKeys: String, CodingKey {
            case minBuild, latestBuild, url, text
        }
    }

    struct Support: Codable, Equatable {
        var url: String? = nil

        enum CodingKeys: String, CodingKey {
            case url
        }
    }

    /// «Авто» and the list (RouteChoice, HomeView).
    struct Routes: Codable, Equatable {
        /// «best»: the panel's АВТО row first (as before); «unlimited»: «⚡ Все направления» first.
        var auto: String? = nil
        /// false: no «Авто · объезды» card (nor the mobile internet check, nor «Поехать объездом»).
        var autoBypass: Bool? = nil
        /// «collapsed» (as before), «expanded» (open until the user closes it), «hidden» (no list).
        var manualList: String? = nil
        /// false: the list without its Объезды tab.
        var bypassTab: Bool? = nil
        /// false: no MobileNetCheck.
        var restrictedDetection: Bool? = nil

        enum CodingKeys: String, CodingKey {
            case auto, autoBypass, manualList, bypassTab, restrictedDetection
        }
    }

    /// The list's road words by its cold check, in ms (RouteTraffic): «свободно» under [good],
    /// «плотное движение» under [calm], «пробки» from there.
    struct Roads: Codable, Equatable {
        var good: Int? = nil
        var calm: Int? = nil

        enum CodingKeys: String, CodingKey {
            case good, calm
        }
    }

    /// false turns each off: the Live Activity (and its row in Профиль), Технический режим (its
    /// entry, and it is left), the motion (the Reduce Motion path), the swipe between tabs.
    struct Features: Codable, Equatable {
        var liveActivity: Bool? = nil
        var techMode: Bool? = nil
        var animations: Bool? = nil
        var tabSwipe: Bool? = nil

        enum CodingKeys: String, CodingKey {
            case liveActivity, techMode, animations, tabSwipe
        }
    }
}

// MARK: - Reading the panel's JSON

// Field by field: one of a wrong type (a string for a number, an object for a flag) is left out
// as if it weren't sent, not the whole config.
extension RemoteConfig {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = c.lenient(Int.self, .version)
        payments = c.lenient(Payments.self, .payments)
        announce = c.lenient(Announce.self, .announce)
        maintenance = c.lenient(Maintenance.self, .maintenance)
        update = c.lenient(Update.self, .update)
        support = c.lenient(Support.self, .support)
        routes = c.lenient(Routes.self, .routes)
        roads = c.lenient(Roads.self, .roads)
        features = c.lenient(Features.self, .features)
    }
}

extension RemoteConfig.Payments {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = c.lenient(Bool.self, .enabled)
        appStore = c.lenient(Bool.self, .appStore)
        otherMethods = c.lenient(Bool.self, .otherMethods)
        cabinet = c.lenient(Bool.self, .cabinet)
        purchaseEndpoint = c.lenient(String.self, .purchaseEndpoint)
    }
}

extension RemoteConfig.Announce {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(String.self, .id)
        text = c.lenient(String.self, .text)
        level = c.lenient(String.self, .level)
        url = c.lenient(String.self, .url)
        dismissible = c.lenient(Bool.self, .dismissible)
    }
}

extension RemoteConfig.Maintenance {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = c.lenient(Bool.self, .enabled)
        text = c.lenient(String.self, .text)
    }
}

extension RemoteConfig.Update {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        minBuild = c.lenient(Int64.self, .minBuild)
        latestBuild = c.lenient(Int64.self, .latestBuild)
        url = c.lenient(String.self, .url)
        text = c.lenient(String.self, .text)
    }
}

extension RemoteConfig.Support {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        url = c.lenient(String.self, .url)
    }
}

extension RemoteConfig.Routes {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        auto = c.lenient(String.self, .auto)
        autoBypass = c.lenient(Bool.self, .autoBypass)
        manualList = c.lenient(String.self, .manualList)
        bypassTab = c.lenient(Bool.self, .bypassTab)
        restrictedDetection = c.lenient(Bool.self, .restrictedDetection)
    }
}

extension RemoteConfig.Roads {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        good = c.lenient(Int.self, .good)
        calm = c.lenient(Int.self, .calm)
    }
}

extension RemoteConfig.Features {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        liveActivity = c.lenient(Bool.self, .liveActivity)
        techMode = c.lenient(Bool.self, .techMode)
        animations = c.lenient(Bool.self, .animations)
        tabSwipe = c.lenient(Bool.self, .tabSwipe)
    }
}

private extension KeyedDecodingContainer {
    /// The field when it is there and of its type, nil otherwise (not thrown).
    func lenient<T: Decodable>(_ type: T.Type, _ key: Key) -> T? {
        try? decodeIfPresent(type, forKey: key)
    }
}

extension RemoteConfig {
    /// The schema this build reads (docs/BACKEND-CONFIG.md). A newer one's known fields still apply.
    static let schema = 1
    /// More than this isn't a config of ours.
    static let maxSize = 64 * 1024

    /// The JSON as the panel sends it; nil unless it is a JSON object (bad JSON: the previous
    /// config stays).
    static func parse(_ data: Data) -> RemoteConfig? {
        guard !data.isEmpty, data.count <= maxSize,
              (try? JSONSerialization.jsonObject(with: data)) is [String: Any] else { return nil }
        return try? JSONDecoder().decode(RemoteConfig.self, from: data)
    }

    /// «titan-config»: base64 of the JSON (standard or URL-safe, padded or not, «base64:» before
    /// it allowed); plain JSON starting with «{» is taken too.
    static func parse(header value: String) -> RemoteConfig? {
        var v = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if v.hasPrefix("{") { return parse(Data(v.utf8)) }
        if v.hasPrefix("base64:") { v = String(v.dropFirst("base64:".count)).trimmingCharacters(in: .whitespaces) }
        v = v.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        if v.count % 4 != 0 { v += String(repeating: "=", count: 4 - v.count % 4) }
        guard let data = Data(base64Encoded: v) else { return nil }
        return parse(data)
    }

    /// An https address with a host, else nil (the config's links are https only).
    static func https(_ string: String?) -> URL? {
        guard let s = string?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty,
              let url = URL(string: s), url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty else { return nil }
        return url
    }

    /// This build's number (CFBundleVersion: the date and time of a TestFlight build, yyMMddHHmm);
    /// 0 if it can't be read, which no minBuild holds back.
    static var build: Int64 {
        Int64((Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "") ?? 0
    }
}

// MARK: - What it means, with today's behaviour for anything not sent

extension RemoteConfig {
    /// payments.enabled: Тарифы and everything on the way to them, the cabinet.
    var paymentsOn: Bool { payments?.enabled != false }
    /// App Store purchases on screen (Тарифы' cards, the code screen's «Проездной через App Store»).
    var appStoreOn: Bool { paymentsOn && payments?.appStore != false }
    /// Другие методы, the cabinet's Тарифы and the ways to the bot and the site
    /// (AppStorePurchases.otherMethods and linksOut add the storefront's rule).
    var otherMethodsOn: Bool { paymentsOn && payments?.otherMethods != false }
    /// Личный кабинет and its card on Проездной.
    var cabinetOn: Bool { paymentsOn && payments?.cabinet != false }
    /// Some way to pay is on: «Продлить проездной», «Добавить ГБ», the expired line's «Продлить».
    var renewOn: Bool { appStoreOn || otherMethodsOn }
    /// Config.purchaseEndpoint (purchases keep being delivered with payments hidden too). Only on
    /// one of the pass's own hosts (Config.hosts): each purchase carries the code of the pass, the
    /// app account token and the cabinet's ID, and those go only to Titan; the config itself may
    /// come from a URL anywhere (titan-config-url). Elsewhere: refused, as if not sent.
    var purchaseEndpoint: URL? {
        guard let url = Self.https(payments?.purchaseEndpoint), let host = url.host?.lowercased(),
              Config.hosts.contains(host) else { return nil }
        return url
    }

    /// purchaseEndpoint sent but refused (not one of our hosts): Технический режим says so.
    var purchaseEndpointRefused: URL? {
        guard purchaseEndpoint == nil else { return nil }
        return Self.https(payments?.purchaseEndpoint)
    }

    /// Поддержка everywhere, over the subscription's support-url (AppStore.supportURL).
    var supportURL: URL? { Self.https(support?.url) }

    /// Маршрут's maintenance line, nil while it is off.
    var maintenanceText: String? {
        guard maintenance?.enabled == true else { return nil }
        return maintenance?.text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? "Идут технические работы. Маршруты могут не работать"
    }

    /// The announcement's card, nil without text. Without an id its text names it (a new text
    /// is a new card, one closed doesn't come back).
    struct Notice: Equatable {
        let id: String
        let text: String
        let warning: Bool
        let url: URL?
        let dismissible: Bool
    }

    var notice: Notice? {
        guard let a = announce, let text = a.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        let id = a.id?.trimmingCharacters(in: .whitespaces).nilIfEmpty ?? "text:" + text
        return Notice(id: id, text: text, warning: a.level?.lowercased() == "warning",
                      url: Self.https(a.url), dismissible: a.dismissible != false)
    }

    /// minBuild above this build: «Обновите Titan» instead of the app.
    var mustUpdate: Bool {
        let build = Self.build
        return build > 0 && (update?.minBuild ?? 0) > build
    }

    /// latestBuild above this build: the card that a newer one is out (its number, for the card
    /// closed for it).
    var newerBuild: Int64? {
        let build = Self.build
        guard build > 0, let latest = update?.latestBuild, latest > build else { return nil }
        return latest
    }

    var updateURL: URL? { Self.https(update?.url) }
    var updateText: String? { update?.text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty }

    /// What «Авто» goes to first (RouteChoice.autoServer).
    enum AutoPick { case best, unlimited }
    var autoPick: AutoPick { routes?.auto?.lowercased() == "unlimited" ? .unlimited : .best }
    /// «Авто · объезды» (its card, the mobile internet check, «Поехать объездом»).
    var autoBypassOn: Bool { routes?.autoBypass != false }

    /// «Выбрать направление вручную»: closed to start with (as before), open until closed by hand,
    /// or none at all.
    enum ManualList { case collapsed, expanded, hidden }
    var manualList: ManualList {
        switch routes?.manualList?.lowercased() {
        case "expanded"?: return .expanded
        case "hidden"?: return .hidden
        default: return .collapsed
        }
    }

    var bypassTabOn: Bool { routes?.bypassTab != false }
    /// MobileNetCheck (it only ever moves to «Авто · объезды», so off with that too).
    var detectionOn: Bool { autoBypassOn && routes?.restrictedDetection != false }

    /// RouteTraffic's thresholds: 500 and 1500 ms unless sent (out of range: as unsent; calm
    /// above good).
    var roadLimits: (good: Int, calm: Int) {
        let good = roads?.good.flatMap { (50...20_000).contains($0) ? $0 : nil } ?? 500
        let calm = roads?.calm.flatMap { $0 > good && $0 <= 60_000 ? $0 : nil } ?? (good < 1500 ? 1500 : good * 3)
        return (good, calm)
    }

    var liveActivityOn: Bool { features?.liveActivity != false }
    var techModeOn: Bool { features?.techMode != false }
    var animationsOn: Bool { features?.animations != false }
    var tabSwipeOn: Bool { features?.tabSwipe != false }
}

// MARK: - Kept on the device

extension RemoteConfig {
    /// The config in force, for code off the main thread (Reminders, RouteTraffic, the Live
    /// Activity's switch); the screens read AppStore.remote, set at the same moment.
    static var current: RemoteConfig { RemoteConfigCache.value }

    /// Where it came from and when (Технический режим), and the URL's ETag.
    struct Source: Codable, Equatable {
        /// "url" or "header".
        var kind: String
        var url: String? = nil
        var etag: String? = nil
        /// The last answer from the URL (200 or 304), or the header's arrival.
        var at: Date
    }

    static var source: Source? {
        guard let data = UserDefaults.standard.data(forKey: RemoteConfigCache.sourceKey) else { return nil }
        return try? JSONDecoder().decode(Source.self, from: data)
    }
}

/// RemoteConfig.current behind a lock: set on the main thread (AppStore.applyRemote), read from
/// anywhere.
enum RemoteConfigCache {
    static let key = "titan.remoteConfig"
    static let sourceKey = "titan.remoteConfigSource"
    private static let lock = NSLock()
    private static var stored: RemoteConfig = {
        guard let data = UserDefaults.standard.data(forKey: key),
              let saved = try? JSONDecoder().decode(RemoteConfig.self, from: data) else { return RemoteConfig() }
        return saved
    }()

    static var value: RemoteConfig {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }

    static func save(_ config: RemoteConfig, source: RemoteConfig.Source) {
        lock.lock()
        stored = config
        lock.unlock()
        if let data = try? JSONEncoder().encode(config) { UserDefaults.standard.set(data, forKey: key) }
        noteSource(source)
    }

    /// The URL answered 304 (or the same config came): only when and its ETag.
    static func noteSource(_ source: RemoteConfig.Source) {
        if let data = try? JSONEncoder().encode(source) { UserDefaults.standard.set(data, forKey: sourceKey) }
    }
}

/// «titan-config-url»: the config GET with the subscription request's own User-Agent and device
/// headers (SubscriptionAPI.deviceHeaders), 10 s, its ETag sent back as If-None-Match.
enum RemoteConfigAPI {
    enum Answer {
        case config(RemoteConfig, etag: String?)
        /// 304 Not Modified: the cached one is the URL's still.
        case unchanged
        /// No answer, not 2xx, not https in the end, or not a config: the cached one stays.
        case failed
    }

    static func fetch(_ url: URL, etag: String?) async -> Answer {
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        req.setValue(Config.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        for (name, value) in SubscriptionAPI.deviceHeaders() { req.setValue(value, forHTTPHeaderField: name) }
        if let etag { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse else { return .failed }
        if http.statusCode == 304 { return etag == nil ? .failed : .unchanged }
        guard (200..<300).contains(http.statusCode), http.url?.scheme?.lowercased() == "https",
              let config = RemoteConfig.parse(data) else { return .failed }
        return .config(config, etag: http.value(forHTTPHeaderField: "ETag")?.nilIfEmpty)
    }
}
