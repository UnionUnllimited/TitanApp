import Foundation
import StoreKit
import UIKit

/// The App Store's auto-renewable subscriptions (one subscription group), in the order Тарифы
/// shows them. Placeholders until they are made in App Store Connect.
enum ProductIDs {
    static let month = "com.titanvps.ios.sub.month"
    static let quarter = "com.titanvps.ios.sub.quarter"
    static let year = "com.titanvps.ios.sub.year"
    static let all = [month, quarter, year]
}

/// Purchases through the App Store (StoreKit 2). A transaction is finished only once the bot
/// has it (deliver): until then StoreKit hands it over again (Transaction.updates at each
/// launch), so a paid purchase isn't lost on the way. The app's own AppStore (Store.swift)
/// shadows StoreKit's, hence StoreKit.AppStore and StoreKit.Transaction (SwiftUI has one too).
@MainActor
final class AppStorePurchases: ObservableObject {
    static let shared = AppStorePurchases()

    /// The subscriptions as the App Store has them, in ProductIDs' order. Empty while none are
    /// set up in App Store Connect.
    @Published private(set) var products: [Product] = []
    @Published private(set) var loading = false
    /// The App Store has answered once (an empty list is then its answer).
    @Published private(set) var loaded = false
    private var updates: Task<Void, Never>?

    enum Outcome: Equatable {
        /// The bot has it: the subscription is extended.
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
    /// the ones not delivered yet (StoreKit sends those once right after launch) come here.
    func start() {
        guard updates == nil else { return }
        updates = Task { [weak self] in
            for await result in StoreKit.Transaction.updates {
                await self?.handle(result)
            }
        }
    }

    func loadProducts() async {
        guard !loading else { return }
        loading = true
        defer {
            loading = false
            loaded = true
        }
        // No network: the last list stays.
        guard let found = try? await Product.products(for: ProductIDs.all) else { return }
        products = found.sorted { (ProductIDs.all.firstIndex(of: $0.id) ?? .max) < (ProductIDs.all.firstIndex(of: $1.id) ?? .max) }
    }

    /// Оплатить через Apple: with the key's appAccountToken, so the bot knows whose it is.
    func purchase(_ product: Product, for subscription: Subscription) async -> Outcome {
        let options: Set<Product.PurchaseOption> = [.appAccountToken(subscription.appAccountToken)]
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
                return await deliver(transaction, jws: verification.jwsRepresentation) ? .delivered : .held
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
    /// each transaction once).
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
        let token = AppStore.current?.subscription?.appAccountToken
        for sequence in [StoreKit.Transaction.unfinished, StoreKit.Transaction.currentEntitlements] {
            for await result in sequence {
                guard case .verified(let t) = result, ProductIDs.all.contains(t.productID),
                      seen.insert(t.id).inserted else { continue }
                if let own = t.appAccountToken, own != token {
                    other = true
                } else if await deliver(t, jws: result.jwsRepresentation) {
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

    /// A transaction StoreKit handed over by itself: to the bot, and the new end date read back.
    private func handle(_ result: VerificationResult<StoreKit.Transaction>) async {
        guard case .verified(let transaction) = result else { return }
        if await deliver(transaction, jws: result.jwsRepresentation) {
            await AppStore.current?.refresh(quiet: true)
        }
    }

    /// The hook to the bot (docs/BACKEND-APPSTORE.md): the transaction as the App Store signed
    /// it (JWS) with the key it is for. Finished only on the bot's 2xx. Kept for next time with
    /// no endpoint yet (Config.purchaseEndpoint), no key, no answer, or another key's purchase
    /// (signed out and in since).
    func deliver(_ transaction: StoreKit.Transaction, jws: String) async -> Bool {
        guard let endpoint = Config.purchaseEndpoint, endpoint.scheme?.lowercased() == "https",
              let sub = AppStore.current?.subscription else { return false }
        if let token = transaction.appAccountToken, token != sub.appAccountToken { return false }
        var body: [String: Any] = [
            "signedTransaction": jws,
            "key": sub.url,
            "appAccountToken": sub.appAccountToken.uuidString.lowercased(),
            // Hints for the bot's log only: it reads them from the verified JWS.
            "transactionId": String(transaction.id),
            "originalTransactionId": String(transaction.originalID),
            "productId": transaction.productID,
        ]
        if let id = AppStore.current?.clientId { body["clientId"] = id }
        var req = URLRequest(url: endpoint, timeoutInterval: 20)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue(Config.userAgent, forHTTPHeaderField: "User-Agent")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard let (_, resp) = try? await URLSession.shared.data(for: req),
              let code = (resp as? HTTPURLResponse)?.statusCode, (200..<300).contains(code) else { return false }
        await transaction.finish()
        return true
    }

    /// The scene on screen: StoreKit's sheets show over it.
    static var activeScene: UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
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
