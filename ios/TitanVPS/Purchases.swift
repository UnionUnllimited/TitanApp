// Payments, renewal and Личный кабинет (docs/HANDOFF.md): this file is compiled only with
// TITAN_PAYMENTS (set for the app in ios/project.yml); the panel's config can hide them
// (RemoteConfig.payments).
#if TITAN_PAYMENTS
import Foundation
import StoreKit
import UIKit

/// What the App Store sells, in the order Тарифы shows it. Placeholders until they are made in
/// App Store Connect. The panel's config may add more (payments.products, schema 2), set the order,
/// badges and notes, and hide some: AppStorePurchases.wanted.
enum ProductIDs {
    /// Auto-renewable subscriptions (one subscription group): the subscription's time.
    static let month = "com.titanvps.ios.sub.month"
    static let quarter = "com.titanvps.ios.sub.quarter"
    static let year = "com.titanvps.ios.sub.year"
    static let subscriptions = [month, quarter, year]
    /// Consumables: Гигабайты для обходов, added to the key's bypass traffic (as the bot's packs).
    static let gbSmall = "com.titanvps.ios.gb.small"
    static let gbMedium = "com.titanvps.ios.gb.medium"
    static let gbLarge = "com.titanvps.ios.gb.large"
    static let packs = [gbSmall, gbMedium, gbLarge]
    static let all = subscriptions + packs
}

/// Purchases through the App Store (StoreKit 2). A transaction is finished only once the bot
/// has it (deliver): until then StoreKit hands it over again (Transaction.updates at each
/// launch), so a paid purchase isn't lost on the way. The app's own AppStore (Store.swift)
/// shadows StoreKit's, hence StoreKit.AppStore and StoreKit.Transaction (SwiftUI has one too).
@MainActor
final class AppStorePurchases: ObservableObject {
    static let shared = AppStorePurchases()

    /// What the App Store has of the products asked for (wanted), as it answered. Empty while
    /// none are set up in App Store Connect. Тарифы reads them through subscriptions and packs.
    @Published private(set) var products: [Product] = []
    @Published private(set) var loading = false
    /// The App Store has answered once (an empty list is then its answer).
    @Published private(set) var loaded = false
    /// The last try failed (no network, the App Store unreachable): Тарифы offers Повторить.
    @Published private(set) var loadFailed = false
    /// The App Store's country (ISO 3166-1 alpha-3), nil until known.
    @Published private(set) var storefront: String?
    private var updates: Task<Void, Never>?
    private var storefronts: Task<Void, Never>?
    /// An offer code redeemed on the code screen (redeemCode(keyless:)): what comes for it until
    /// then signs in, as a purchase made there.
    private var keylessRedeemUntil: Date?

    /// The ids the last load asked for: a new one in the config's list asks again (wantedChanged).
    private var asked: Set<String> = []

    /// What the App Store is asked for, in Тарифы' order: the config's products first (the panel's
    /// order), then ProductIDs not listed there; those it hides left out (RemoteConfig.storeProductIds).
    static var wanted: [String] { RemoteConfig.current.storeProductIds(builtIn: ProductIDs.all) }

    /// The products in the order the config wants now, those it hides now left out; told apart by
    /// their type (subscriptions auto-renewable, the packs of гигабайты consumable), whatever their id.
    private var shown: [Product] {
        let order = Self.wanted
        let place = Dictionary(order.enumerated().map { ($0.element, $0.offset) }, uniquingKeysWith: { a, _ in a })
        return products.filter { place[$0.id] != nil }.sorted { (place[$0.id] ?? .max) < (place[$1.id] ?? .max) }
    }

    var subscriptions: [Product] { shown.filter { $0.type == .autoRenewable } }
    var packs: [Product] { shown.filter { $0.type == .consumable } }

    /// Другие методы in Тарифы, the in-app cabinet's tariffs and codes (the site's own payment)
    /// and the other ways to the bot and the site (the key entry's «Где взять ключ», Подписка's
    /// «Открыть сайт», О приложении's bot): in every storefront, as the owner chose. App Review
    /// allows them only in the United States storefront (3.1.1(a), 3.1.3): false keeps them all
    /// there (docs/HANDOFF.md). The panel's config turns them off everywhere (payments.otherMethods).
    static let otherMethodsEverywhere = true
    var otherMethods: Bool {
        RemoteConfig.current.otherMethodsOn && (Self.otherMethodsEverywhere || storefront == "USA")
    }
    /// The ways to the bot and the site outside Тарифы: the same switch.
    var linksOut: Bool { otherMethods }

    /// The App Store answered without any of the subscriptions (not made in App Store Connect
    /// yet) or couldn't be reached: Тарифы then shows Другие методы with a key anyway.
    var noSubscriptions: Bool { (loaded || loadFailed) && subscriptions.isEmpty }

    enum Outcome: Equatable {
        /// The bot has it: the subscription is extended (or, without a key, made and signed in).
        case delivered
        /// Bought, not with the bot yet (no endpoint, no network): StoreKit gives it again.
        case held
        /// Waiting for Ask to Buy or the bank.
        case pending
        case cancelled
        /// Восстановить покупки found nothing.
        case nothing
        /// Восстановить покупки found only purchases made with another key.
        case otherKey
        case failed(String)
    }

    /// At app start: renewals, purchases on another device, Ask to Buy approvals, refunds and
    /// the ones not delivered yet (StoreKit sends those once right after launch) come here;
    /// the storefront is read, and again when the Apple ID's country changes.
    func start() {
        guard updates == nil else { return }
        updates = Task { [weak self] in
            for await result in StoreKit.Transaction.updates {
                await self?.handle(result)
            }
        }
        storefronts = Task { [weak self] in
            let country = await StoreKit.Storefront.current?.countryCode
            self?.storefront = country
            for await s in StoreKit.Storefront.updates {
                self?.storefront = s.countryCode
            }
        }
    }

    /// The products of `wanted` from the App Store; asked again (twice more at most) when the
    /// config listed a new one while it answered.
    func loadProducts() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        for _ in 0..<3 {
            let ids = Self.wanted
            do {
                products = try await Product.products(for: ids)
                asked = Set(ids)
                loaded = true
                loadFailed = false
            } catch {
                // The last list stays.
                loadFailed = true
                return
            }
            if Set(Self.wanted).isSubset(of: asked) { return }
        }
    }

    /// The config's products changed (AppStore.applyRemote): order and hidden ones follow by
    /// themselves (shown); one not asked for yet is asked for now, once the App Store has been asked
    /// at all (Тарифы and the code screen ask as they open). A load under way asks again itself.
    func wantedChanged() async {
        guard loaded || loadFailed, !loading, !Set(Self.wanted).isSubset(of: asked) else { return }
        await loadProducts()
    }

    /// Оплатить через Apple: with the key's appAccountToken, so the bot knows whose it is.
    /// Before a key is entered, with this device's own token: the bot then makes a subscription
    /// and sends its key back (deliver).
    func purchase(_ product: Product, for subscription: Subscription?) async -> Outcome {
        let token = subscription?.appAccountToken ?? makeKeylessToken()
        let options: Set<Product.PurchaseOption> = [.appAccountToken(token)]
        do {
            let result: Product.PurchaseResult
            if #available(iOS 17.0, *), let scene = Self.activeScene {
                // The confirmation over the app's own window: without one iOS 18.2+ may find none.
                result = try await product.purchase(confirmIn: scene, options: options)
            } else {
                result = try await product.purchase(options: options)
            }
            switch result {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    return .failed("App Store не подтвердил покупку")
                }
                return await deliver(transaction, jws: verification.jwsRepresentation, signIn: true) ? .delivered : .held
            case .pending:
                return .pending
            case .userCancelled:
                return .cancelled
            @unknown default:
                return .cancelled
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// Восстановить покупки: the Apple ID's purchases synced (iOS may ask for its password),
    /// then the undelivered ones and the running subscription go to the bot again (it counts
    /// each transaction once). Before a key is entered the subscription brings its key back.
    func restore() async -> Outcome {
        do {
            try await StoreKit.AppStore.sync()
        } catch {
            if case StoreKitError.userCancelled = error { return .cancelled }
            return .failed(error.localizedDescription)
        }
        var seen: Set<UInt64> = []
        var delivered = false
        var held = false
        var other = false
        for sequence in [StoreKit.Transaction.unfinished, StoreKit.Transaction.currentEntitlements] {
            for await result in sequence {
                // Ours by id, or by type: one the config listed (and maybe no longer lists) too.
                guard case .verified(let t) = result, Self.isOwnProduct(t),
                      seen.insert(t.id).inserted else { continue }
                // Read for each one: the first delivered without a key signs in.
                let sub = AppStore.current?.subscription
                if let sub, !isOurs(t, sub) {
                    other = true
                } else if sub == nil, t.productType != .autoRenewable {
                    // Гигабайты need a key to go to.
                    continue
                } else if await deliver(t, jws: result.jwsRepresentation, signIn: true) {
                    delivered = true
                } else {
                    held = true
                }
            }
        }
        if delivered { return .delivered }
        if held { return .held }
        return other ? .otherKey : .nothing
    }

    /// Удалить аккаунт: an App Store subscription keeps being billed by Apple until it is
    /// cancelled there, so the user is told first.
    func hasSubscription() async -> Bool {
        for await result in StoreKit.Transaction.currentEntitlements {
            if case .verified(let t) = result, t.productType == .autoRenewable || ProductIDs.subscriptions.contains(t.productID),
               t.revocationDate == nil {
                return true
            }
        }
        return false
    }

    /// Управление подпиской: StoreKit's own sheet over the app; the App Store's page if it
    /// doesn't open.
    func manageSubscriptions() async {
        if let scene = Self.activeScene {
            do {
                try await StoreKit.AppStore.showManageSubscriptions(in: scene)
                return
            } catch {}
        }
        _ = await UIApplication.shared.open(Config.manageSubscriptions)
    }

    /// Ввести код предложения: an App Store offer code (made in App Store Connect; the site's
    /// codes aren't for the App Store). What it gives comes in Transaction.updates, without a
    /// token: with a key it goes to that key; on the code screen ([keyless]) the bot makes the pass
    /// for it and sends its code back, as for a purchase there (handle, for the next 10 minutes).
    func redeemCode(keyless: Bool = false) async {
        guard let scene = Self.activeScene else { return }
        if keyless { keylessRedeemUntil = Date().addingTimeInterval(600) }
        try? await StoreKit.AppStore.presentOfferCodeRedeemSheet(in: scene)
        // One that came in while the sheet was up.
        if keyless { await deliverUnfinished() }
    }

    /// The transactions not finished yet (no endpoint until the panel's config sent one, no
    /// answer): to the bot again now, as StoreKit hands them over at launch.
    func deliverUnfinished() async {
        for await result in StoreKit.Transaction.unfinished {
            await handle(result)
        }
    }

    /// A transaction StoreKit handed over by itself: to the bot, and the new end date read back.
    /// Signed out, only this device's own purchase made before a key (not a renewal: after
    /// Выйти the app doesn't sign in again by itself), or an offer code just redeemed on the code
    /// screen.
    private func handle(_ result: VerificationResult<StoreKit.Transaction>) async {
        guard case .verified(let transaction) = result else { return }
        let signedIn = AppStore.current?.subscription != nil
        let redeemed = !signedIn && transaction.appAccountToken == nil
            && keylessRedeemUntil.map { Date() < $0 } == true
        guard await deliver(transaction, jws: result.jwsRepresentation, signIn: redeemed) else { return }
        if signedIn {
            await AppStore.current?.refresh(quiet: true)
        } else {
            AppStore.current?.showToast("Проездной оформлен", .success)
        }
    }

    /// One of the app's own products: ProductIDs, or a subscription or a pack of гигабайты the
    /// panel's config added (payments.products), told apart by type as Тарифы does.
    private static func isOwnProduct(_ t: StoreKit.Transaction) -> Bool {
        ProductIDs.all.contains(t.productID) || t.productType == .autoRenewable || t.productType == .consumable
    }

    /// Bought with this key, with this device's own token, or with none (an offer code).
    private func isOurs(_ t: StoreKit.Transaction, _ sub: Subscription) -> Bool {
        guard let token = t.appAccountToken else { return true }
        return token == sub.appAccountToken || token == keylessToken
    }

    /// The hook to the bot (docs/BACKEND-APPSTORE.md): the transaction as the App Store signed
    /// it (JWS) with the key it is for. Without a key (bought or restored before one was
    /// entered: [signIn]) the bot answers with the key of the subscription it made or found
    /// for it, and the app signs in with it. Finished only on the bot's 2xx (and, without a
    /// key, once signed in). Kept for next time with no endpoint yet (Config.purchaseEndpoint),
    /// no answer, or another key's purchase (signed out and in since).
    func deliver(_ transaction: StoreKit.Transaction, jws: String, signIn: Bool = false) async -> Bool {
        guard let endpoint = Config.purchaseEndpoint, let store = AppStore.current else { return false }
        let sub = store.subscription
        var body: [String: Any] = [
            "signedTransaction": jws,
            // Hints for the bot's log only: it reads them from the verified JWS.
            "transactionId": String(transaction.id),
            "originalTransactionId": String(transaction.originalID),
            "productId": transaction.productID,
        ]
        if let sub {
            guard isOurs(transaction, sub) else { return false }
            body["key"] = sub.url
            body["appAccountToken"] = sub.appAccountToken.uuidString.lowercased()
            if let id = store.clientId { body["clientId"] = id }
        } else {
            // No key: a subscription only, asked for by a tap or this device's own first purchase.
            let own = transaction.appAccountToken != nil && transaction.appAccountToken == keylessToken
                && transaction.id == transaction.originalID
            guard transaction.productType == .autoRenewable, signIn || own else { return false }
        }
        guard let (data, code) = await BotAPI.post(endpoint, body), (200..<300).contains(code) else { return false }
        if sub == nil {
            // Signed in with the key the bot sent back; signed in meanwhile, or no key: kept.
            let key = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["key"] as? String
            guard let key, store.subscription == nil, await store.activate(key) else { return false }
        }
        await transaction.finish()
        return true
    }

    /// A purchase before a key was entered carries this device's own token: the purchase and
    /// its renewals are then known as this device's. Made once; gone with Удалить аккаунт.
    private static let keylessKey = "titan.storeToken"

    private var keylessToken: UUID? {
        UserDefaults.standard.string(forKey: Self.keylessKey).flatMap(UUID.init(uuidString:))
    }

    private func makeKeylessToken() -> UUID {
        if let token = keylessToken { return token }
        let token = UUID()
        UserDefaults.standard.set(token.uuidString, forKey: Self.keylessKey)
        return token
    }

    func forgetKeylessToken() { UserDefaults.standard.removeObject(forKey: Self.keylessKey) }

    /// The scene on screen: StoreKit's sheets show over it.
    static var activeScene: UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }
}

extension AppStorePurchases.Outcome {
    /// What Тарифы and the key entry say about it, nil for nothing to say. [keyless]: bought or
    /// restored before a key was entered; [pack]: Гигабайты для обходов.
    func note(restored: Bool, keyless: Bool, pack: Bool = false) -> (text: String, warning: Bool)? {
        let what = keyless ? "код проездного появится в приложении" : pack ? "гигабайты добавятся" : "проездной продлится"
        switch self {
        case .delivered:
            if restored { return ("Покупки восстановлены", false) }
            return (keyless ? "Проездной оформлен" : pack ? "Гигабайты добавлены" : "Проездной продлён", false)
        case .held:
            return ((restored ? "Покупка найдена — " : "Покупка получена — ") + what + " после проверки", false)
        case .pending:
            return ("Покупка ждёт подтверждения — \(what), когда App Store её одобрит", false)
        case .nothing:
            return ("Покупок в App Store не найдено", false)
        case .otherKey:
            return ("Проездной в App Store оформлен с другим кодом — напишите в поддержку", true)
        case .cancelled:
            return nil
        case .failed(let why):
            return ((restored ? "Не удалось восстановить покупки: " : "Покупка не прошла: ") + why, true)
        }
    }
}

extension Product {
    /// "каждый месяц", "каждые 3 месяца": how often it renews.
    var renewal: String {
        guard let p = subscription?.subscriptionPeriod else { return "" }
        let n = p.value
        switch p.unit {
        case .day: return n == 7 ? "каждую неделю" : n == 1 ? "каждый день" : "каждые \(n) \(Self.word(n, "день", "дня", "дней"))"
        case .week: return n == 1 ? "каждую неделю" : "каждые \(n) \(Self.word(n, "неделю", "недели", "недель"))"
        case .month: return n == 1 ? "каждый месяц" : "каждые \(n) \(Self.word(n, "месяц", "месяца", "месяцев"))"
        case .year: return n == 1 ? "каждый год" : "каждые \(n) \(Self.word(n, "год", "года", "лет"))"
        @unknown default: return ""
        }
    }

    /// "1 месяц", "3 месяца": the name when App Store Connect gives none.
    var periodName: String {
        guard let p = subscription?.subscriptionPeriod else { return displayName }
        let n = p.value
        switch p.unit {
        case .day: return "\(n) \(Self.word(n, "день", "дня", "дней"))"
        case .week: return "\(n) \(Self.word(n, "неделя", "недели", "недель"))"
        case .month: return "\(n) \(Self.word(n, "месяц", "месяца", "месяцев"))"
        case .year: return "\(n) \(Self.word(n, "год", "года", "лет"))"
        @unknown default: return displayName
        }
    }

    /// The Russian plural for [n]: 1 месяц, 3 месяца, 5 месяцев.
    private static func word(_ n: Int, _ one: String, _ few: String, _ many: String) -> String {
        if (11...14).contains(n % 100) { return many }
        if n % 10 == 1 { return one }
        return (2...4).contains(n % 10) ? few : many
    }
}
#endif
