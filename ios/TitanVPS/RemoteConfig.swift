import Foundation

/// The owner's switches from the backend panel («включать и отключать многое», docs/BACKEND-CONFIG.md).
/// Every field is optional and nothing sent means today's behaviour, so an empty or missing config
/// changes nothing. It comes with the subscription, in its response headers: «titan-config» (the
/// JSON in base64) or «titan-config-url» (an https address to GET it from, the URL winning); with
/// neither the cached one stays. Kept in UserDefaults and in force from launch, without the
/// network (AppStore.remote, RemoteConfig.current off the main thread). Not secret: it opens
/// nothing, anyone with the pass can read it. Schema 2 adds the App Store products' order, badges
/// and notes (payments.products: ProductInfo), the panel's own cards, buttons and rows (blocks:
/// RemoteBlock, AppStore.blocks(for:)) and its words for a few labels (texts: TitanText).
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
    /// Schema 2: as sent (Block), read into RemoteBlock by allBlocks.
    var blocks: [Block]? = nil
    /// Schema 2: TitanText's key → the label's words (text(_:)).
    var texts: [String: String]? = nil

    enum CodingKeys: String, CodingKey {
        case version, payments, announce, maintenance, update, support, routes, roads, features, blocks, texts
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
        /// Schema 2: App Store products besides ProductIDs, the order Тарифы shows them in, their
        /// badges and notes, those hidden (ProductInfo, storeProductIds(builtIn:)).
        var products: [ProductEntry]? = nil

        enum CodingKeys: String, CodingKey {
            case enabled, appStore, otherMethods, cabinet, purchaseEndpoint, products
        }

        struct ProductEntry: Codable, Equatable {
            var id: String? = nil
            var badge: String? = nil
            var note: String? = nil
            var hidden: Bool? = nil

            enum CodingKeys: String, CodingKey {
                case id, badge, note, hidden
            }
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

    /// Schema 2: one of the panel's blocks as sent (every field may be missing or wrong here);
    /// RemoteBlock is what this build makes of it.
    struct Block: Codable, Equatable {
        var id: String? = nil
        var slot: String? = nil
        var kind: String? = nil
        var title: String? = nil
        var text: String? = nil
        var icon: String? = nil
        var tone: String? = nil
        var button: String? = nil
        var action: Action? = nil
        var dismissible: Bool? = nil
        var when: When? = nil

        enum CodingKeys: String, CodingKey {
            case id, slot, kind, title, text, icon, tone, button, action, dismissible, when
        }

        struct Action: Codable, Equatable {
            var type: String? = nil
            var value: String? = nil
            var inApp: Bool? = nil

            enum CodingKeys: String, CodingKey {
                case type, value, inApp
            }
        }

        /// from and until: unix seconds.
        struct When: Codable, Equatable {
            var status: [String]? = nil
            var onTrip: Bool? = nil
            var minBuild: Int64? = nil
            var maxBuild: Int64? = nil
            var from: Double? = nil
            var until: Double? = nil

            enum CodingKeys: String, CodingKey {
                case status, onTrip, minBuild, maxBuild, from, until
            }
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
        blocks = c.lenientList(Block.self, .blocks)
        texts = c.lenientMap(String.self, .texts)
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
        products = c.lenientList(ProductEntry.self, .products)
    }
}

extension RemoteConfig.Payments.ProductEntry {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(String.self, .id)
        badge = c.lenient(String.self, .badge)
        note = c.lenient(String.self, .note)
        hidden = c.lenient(Bool.self, .hidden)
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

extension RemoteConfig.Block {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(String.self, .id)
        slot = c.lenient(String.self, .slot)
        kind = c.lenient(String.self, .kind)
        title = c.lenient(String.self, .title)
        text = c.lenient(String.self, .text)
        icon = c.lenient(String.self, .icon)
        tone = c.lenient(String.self, .tone)
        button = c.lenient(String.self, .button)
        action = c.lenient(Action.self, .action)
        dismissible = c.lenient(Bool.self, .dismissible)
        when = c.lenient(When.self, .when)
    }
}

extension RemoteConfig.Block.Action {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = c.lenient(String.self, .type)
        value = c.lenient(String.self, .value)
        inApp = c.lenient(Bool.self, .inApp)
    }
}

extension RemoteConfig.Block.When {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        status = c.lenientList(String.self, .status)
        onTrip = c.lenient(Bool.self, .onTrip)
        minBuild = c.lenient(Int64.self, .minBuild)
        maxBuild = c.lenient(Int64.self, .maxBuild)
        from = c.lenient(Double.self, .from)
        until = c.lenient(Double.self, .until)
    }
}

/// One element of a list (or a value of an object) read on its own: one of another type is left
/// out, not the whole list.
private struct Tolerant<T: Decodable>: Decodable {
    let value: T?

    init(from decoder: Decoder) throws {
        value = try? T(from: decoder)
    }
}

private extension KeyedDecodingContainer {
    /// The field when it is there and of its type, nil otherwise (not thrown).
    func lenient<T: Decodable>(_ type: T.Type, _ key: Key) -> T? {
        try? decodeIfPresent(type, forKey: key)
    }

    /// A list: nil unless the field is one; its elements not of the type left out.
    func lenientList<T: Decodable>(_ type: T.Type, _ key: Key) -> [T]? {
        guard let list = try? decodeIfPresent([Tolerant<T>].self, forKey: key) else { return nil }
        return list.compactMap { $0.value }
    }

    /// An object of values: nil unless the field is one; its values not of the type left out.
    func lenientMap<T: Decodable>(_ type: T.Type, _ key: Key) -> [String: T]? {
        guard let map = try? decodeIfPresent([String: Tolerant<T>].self, forKey: key) else { return nil }
        return map.compactMapValues { $0.value }
    }
}

extension RemoteConfig {
    /// The schema this build reads (docs/BACKEND-CONFIG.md): 1 and 2 alike (2 is 1 and more fields);
    /// a newer one's known fields still apply, and so do schema 2's fields in a document marked 1.
    static let schema = 2
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

// MARK: - Schema 2: products, blocks, texts

extension RemoteConfig {
    /// An id as the contract has it: 1...[max] of A–Z, a–z, 0–9, «.», «_» and «-»; nil otherwise.
    static func validId(_ s: String?, max: Int) -> String? {
        guard let s, (1...max).contains(s.count), s.unicodeScalars.allSatisfy({ idCharacters.contains($0) }) else { return nil }
        return s
    }

    private static let idCharacters = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-")

    /// Words as the contract has them: trimmed, 1...[max] characters; nil when empty or longer.
    static func shortText(_ s: String?, max: Int) -> String? {
        guard let s = s?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty, s.count <= max else { return nil }
        return s
    }

    /// payments.products as this build reads them: an id within limits, badge (20) and note (80)
    /// dropped when longer, the first of each id, in the panel's order, the hidden ones too.
    struct ProductInfo: Equatable {
        let id: String
        let badge: String?
        let note: String?
        let hidden: Bool
    }

    var productList: [ProductInfo] {
        var seen: Set<String> = []
        return (payments?.products ?? []).compactMap { (p: Payments.ProductEntry) -> ProductInfo? in
            guard let id = Self.validId(p.id, max: 100), seen.insert(id).inserted else { return nil }
            return ProductInfo(id: id, badge: Self.shortText(p.badge, max: 20), note: Self.shortText(p.note, max: 80),
                               hidden: p.hidden == true)
        }
    }

    /// The panel's products Тарифы shows, in its order (the hidden ones left out).
    var products: [ProductInfo] { productList.filter { !$0.hidden } }

    /// A product's badge and note, nil for one the panel doesn't list (or hides).
    func product(_ id: String) -> ProductInfo? { products.first { $0.id == id } }

    /// What the App Store is asked for, in the order Тарифы shows it (AppStorePurchases): the
    /// panel's products first, in its order, then [builtIn] (ProductIDs.all) not listed, in
    /// theirs; those it hides left out. Prices are always the App Store's.
    func storeProductIds(builtIn: [String]) -> [String] {
        let list = productList
        let listed = Set(list.map(\.id))
        return list.filter { !$0.hidden }.map(\.id) + builtIn.filter { !listed.contains($0) }
    }

    /// The first 20 of blocks are read; at most 3 per place are shown (AppStore.blocks(for:)).
    static let maxBlocks = 20
    static let blocksPerPlace = 3

    /// blocks as this build reads them (RemoteBlock.init): of the first 20 sent, those that can be
    /// one (an id, place and title within limits, an action it can do), the first of each id.
    var allBlocks: [RemoteBlock] {
        var seen: Set<String> = []
        return (blocks ?? []).prefix(Self.maxBlocks).compactMap { (raw: Block) -> RemoteBlock? in
            guard let b = RemoteBlock(raw), seen.insert(b.id).inserted else { return nil }
            return b
        }
    }

    /// Those for [slot], in the panel's order, before their conditions: AppStore.blocks(for:) gives
    /// the ones to show now.
    func blocks(for slot: RemoteBlock.Slot) -> [RemoteBlock] { allBlocks.filter { $0.slot == slot } }

    /// texts[key] as sent: trimmed, 1...40 characters; nil otherwise (the built-in words then).
    func textOverride(_ key: TitanText) -> String? { Self.shortText(texts?[key.rawValue], max: 40) }

    /// The label on screen: the panel's words for it, else [fallback] (a longer VoiceOver label
    /// for the built-in words), else the built-in words.
    func text(_ key: TitanText, _ fallback: String? = nil) -> String {
        textOverride(key) ?? fallback ?? key.builtIn
    }

    /// The labels the panel rewords now (Технический режим).
    var overriddenTexts: [TitanText] { TitanText.allCases.filter { textOverride($0) != nil } }
}

/// The labels the panel may reword (texts, schema 2): each one's own button or title only, not
/// every sentence that mentions it; the Live Activity, Siri and the widgets keep these words.
enum TitanText: String, CaseIterable {
    /// RoutePill standing, and on the way.
    case routeGo = "route.go"
    case routeStop = "route.stop"
    /// The «Авто» and «Авто · объезды» cards: their titles and the line under them.
    case autoTitle = "choice.auto.title"
    case autoText = "choice.auto.text"
    case bypassTitle = "choice.bypass.title"
    case bypassText = "choice.bypass.text"
    /// The row that opens the list, and Проверить дороги over it.
    case listManual = "list.manual"
    case listRoads = "list.roads"
    /// Тарифы' title and its Другие методы.
    case tariffsTitle = "tariffs.title"
    case tariffsOther = "tariffs.other"
    /// Проездной's (and the cabinet's) «Продлить проездной», the «Добавить ГБ» buttons.
    case passRenew = "pass.renew"
    case passAddGb = "pass.addGb"

    /// This build's own words.
    var builtIn: String {
        switch self {
        case .routeGo: return "Поехали"
        case .routeStop: return "Остановиться"
        case .autoTitle: return "Авто"
        case .autoText: return "Сам выбирает быстрое направление"
        case .bypassTitle: return "Авто · объезды"
        case .bypassText: return "Когда мобильный интернет ограничен"
        case .listManual: return "Выбрать направление вручную"
        case .listRoads: return "Проверить дороги"
        case .tariffsTitle: return "Тарифы"
        case .tariffsOther: return "Другие методы"
        case .passRenew: return "Продлить проездной"
        case .passAddGb: return "Добавить ГБ"
        }
    }
}

/// One of the panel's blocks (blocks, schema 2) as this build reads it: a card, a button or a row
/// in one of the screens' places, drawn by the screens; what is shown of them now is
/// AppStore.blocks(for:). Its words are shown as sent.
struct RemoteBlock: Equatable, Identifiable {
    /// The places: Маршрут's top and bottom, Проездной's, Профиль's and Тарифы' top.
    enum Slot: String, CaseIterable {
        case routeTop = "route.top"
        case routeBottom = "route.bottom"
        case passTop = "pass.top"
        case profileTop = "profile.top"
        case tariffsTop = "tariffs.top"
    }

    enum Kind: String { case card, button, row }
    enum Tone: String { case accent, neutral, warn, success }

    /// The panel's icons, each an SF Symbol of iOS 16.
    enum Icon: String, CaseIterable {
        case gift, star, bolt, bell, info, warning, ticket, chat, sparkles, heart, clock, map, phone, check, percent, megaphone

        var symbol: String {
            switch self {
            case .gift: return "gift.fill"
            case .star: return "star.fill"
            case .bolt: return "bolt.fill"
            case .bell: return "bell.fill"
            case .info: return "info.circle.fill"
            case .warning: return "exclamationmark.triangle.fill"
            case .ticket: return "ticket.fill"
            case .chat: return "bubble.left.and.bubble.right.fill"
            case .sparkles: return "sparkles"
            case .heart: return "heart.fill"
            case .clock: return "clock.fill"
            case .map: return "map.fill"
            case .phone: return "iphone"
            case .check: return "checkmark.seal.fill"
            case .percent: return "percent"
            case .megaphone: return "megaphone.fill"
            }
        }
    }

    /// A tab, for «screen»: Маршрут, Проездной, Профиль.
    enum Screen: String { case route, pass, profile }

    enum Action: Equatable {
        /// https only; inApp: in the app's own browser, else Safari.
        case url(URL, inApp: Bool)
        case bot, tariffs, packs, cabinet, support
        case screen(Screen)
        /// The words to copy, 200 characters at most.
        case copy(String)
        /// The app's own titanvps:// link.
        case deeplink(URL)
    }

    enum PassStatus: String, CaseIterable { case active, expired }

    /// When it is shown: the pass's status, on the way or standing, this build, the time.
    struct When: Equatable {
        var status: Set<PassStatus> = Set(PassStatus.allCases)
        /// nil: either way; true: only on the way; false: only standing.
        var onTrip: Bool? = nil
        /// CFBundleVersion's range, inclusive; 0: no bound.
        var minBuild: Int64 = 0
        var maxBuild: Int64 = 0
        var from: Date? = nil
        var until: Date? = nil

        /// [pass]: nil without one, where only a block for both statuses is shown.
        func allows(pass: PassStatus?, onTrip trip: Bool, build: Int64, now: Date) -> Bool {
            refusal(pass: pass, onTrip: trip, build: build, now: now) == nil
        }

        /// Which condition keeps it off screen now (Технический режим's words), nil when none does.
        func refusal(pass: PassStatus?, onTrip trip: Bool, build: Int64, now: Date) -> String? {
            if let pass {
                if !status.contains(pass) {
                    return "status: проездной \(pass.rawValue), нужен " + status.map(\.rawValue).sorted().joined(separator: ", ")
                }
            } else if status.count < PassStatus.allCases.count {
                return "status: кода проездного нет"
            }
            if let onTrip, onTrip != trip { return onTrip ? "onTrip: только в пути" : "onTrip: только пока стоим" }
            if minBuild > 0 && build < minBuild { return "minBuild \(minBuild), сборка \(build)" }
            if maxBuild > 0 && build > maxBuild { return "maxBuild \(maxBuild), сборка \(build)" }
            if let from, now < from { return "from ещё не наступило" }
            if let until, now > until { return "until уже прошло" }
            return nil
        }
    }

    /// Its id, also what a close by hand is remembered by (AppStore.closeBlock).
    let id: String
    let slot: Slot
    let kind: Kind
    let title: String
    /// A card's and a row's line under the title (none on a button).
    let text: String?
    let icon: Icon?
    let tone: Tone
    /// A card's button; without it the whole card is the button.
    let button: String?
    /// nil: information only.
    let action: Action?
    /// A close button, remembered by id on the device.
    let dismissible: Bool
    let when: When
}

extension RemoteBlock {
    /// The panel's block as sent; nil when it can't be one: no id (64), place or title (60) within
    /// limits, or an action this build can never do (an unknown type, a link that isn't https or
    /// titanvps://, an unknown screen, nothing to copy). Text over 200 and a button over 24
    /// characters are dropped, the block stays; an unknown kind is a card, an unknown tone
    /// neutral, an unknown icon none. An action of the wrong type (not an object) is as if not
    /// sent: information only.
    init?(_ b: RemoteConfig.Block) {
        guard let id = RemoteConfig.validId(b.id, max: 64),
              let slot = b.slot.flatMap({ Slot(rawValue: $0.lowercased()) }),
              let title = RemoteConfig.shortText(b.title, max: 60) else { return nil }
        var action: Action?
        if let raw = b.action {
            guard let a = Action(raw) else { return nil }
            action = a
        }
        let kind = b.kind.flatMap { Kind(rawValue: $0.lowercased()) } ?? .card
        self.init(id: id, slot: slot, kind: kind, title: title,
                  text: kind == .button ? nil : RemoteConfig.shortText(b.text, max: 200),
                  icon: b.icon.flatMap { Icon(rawValue: $0.lowercased()) },
                  tone: b.tone.flatMap { Tone(rawValue: $0.lowercased()) } ?? .neutral,
                  button: kind == .card ? RemoteConfig.shortText(b.button, max: 24) : nil,
                  action: action, dismissible: b.dismissible == true, when: When(b.when))
    }
}

extension RemoteBlock {
    /// Технический режим: why the panel's block [b] makes none in this build (init gives nil), nil
    /// when it makes one.
    static func rejection(_ b: RemoteConfig.Block) -> String? {
        if RemoteConfig.validId(b.id, max: 64) == nil { return "id: нет или не 1–64 символа A–Z a–z 0–9 . _ -" }
        if b.slot.flatMap({ Slot(rawValue: $0.lowercased()) }) == nil { return "slot: неизвестное место" }
        if RemoteConfig.shortText(b.title, max: 60) == nil { return "title: нет или длиннее 60" }
        if let a = b.action, Action(a) == nil {
            switch a.type?.lowercased() {
            case "url"?: return "action: url не https"
            case "screen"?: return "action: screen не route, pass или profile"
            case "copy"?: return "action: copy пустой или длиннее 200"
            case "deeplink"?: return "action: deeplink не titanvps://"
            default: return "action: type «\(a.type ?? "")» неизвестен"
            }
        }
        return nil
    }
}

extension RemoteBlock.Action {
    init?(_ a: RemoteConfig.Block.Action) {
        let value = a.value?.trimmingCharacters(in: .whitespacesAndNewlines)
        switch a.type?.lowercased() {
        case "url"?:
            guard let url = RemoteConfig.https(value) else { return nil }
            self = .url(url, inApp: a.inApp == true)
        case "bot"?: self = .bot
        case "tariffs"?: self = .tariffs
        case "packs"?: self = .packs
        case "cabinet"?: self = .cabinet
        case "support"?: self = .support
        case "screen"?:
            guard let screen = value.flatMap({ RemoteBlock.Screen(rawValue: $0.lowercased()) }) else { return nil }
            self = .screen(screen)
        case "copy"?:
            guard let text = RemoteConfig.shortText(value, max: 200) else { return nil }
            self = .copy(text)
        case "deeplink"?:
            guard let value, value.lowercased().hasPrefix("titanvps://"), let url = URL(string: value) else { return nil }
            self = .deeplink(url)
        default:
            return nil
        }
    }
}

extension RemoteBlock.When {
    /// Statuses it doesn't know are left out (none left: both); a bound of 0 or less is none.
    init(_ w: RemoteConfig.Block.When?) {
        self.init()
        guard let w else { return }
        let statuses = Set((w.status ?? []).compactMap { RemoteBlock.PassStatus(rawValue: $0.lowercased()) })
        if !statuses.isEmpty { status = statuses }
        onTrip = w.onTrip
        minBuild = max(w.minBuild ?? 0, 0)
        maxBuild = max(w.maxBuild ?? 0, 0)
        from = w.from.flatMap { $0 > 0 ? Date(timeIntervalSince1970: $0) : nil }
        until = w.until.flatMap { $0 > 0 ? Date(timeIntervalSince1970: $0) : nil }
    }
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
        /// The last time what came was another config than the one in force.
        var changed: Date? = nil
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
    /// The panel's blocks closed by hand (AppStore.closeBlock): their ids, the last 200.
    static let closedBlocksKey = "titan.blocksClosed"
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
