import StoreKit
import SwiftUI

/// Why Тарифы opened: the line under its title says so.
enum PaywallReason: String, Identifiable {
    case renew, expired, traffic
    /// From the key entry, before a key: a subscription bought here brings its key.
    case start
    var id: String { rawValue }
}

/// Тарифы: the App Store first (its subscriptions, and for «Добавить ГБ» its packs of
/// гигабайты; Оплатить через Apple, Восстановить покупки, Управление подпиской, its offer
/// codes, the auto-renewal terms), then Другие методы: the site's cabinet in the browser,
/// without a price beside it (showsOther). Opened from Главная's «Подписка истекла»,
/// Подписка's «Продлить подписку», the «Добавить ГБ» buttons and the key entry.
struct PaywallView: View {
    let reason: PaywallReason
    @EnvironmentObject var store: AppStore
    @ObservedObject private var purchases = AppStorePurchases.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var picked: String?
    @State private var pickedPack: String?
    /// The product being bought: its card's button spins, every other waits.
    @State private var buying: String?
    @State private var restoring = false
    @State private var note: Note?

    private struct Note: Equatable {
        let text: String
        var warning = false
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    header
                    if let note {
                        banner(note).transition(.opacity.combined(with: .move(edge: .top)))
                    }
                    // Гигабайты go to a key; none in the App Store (not made yet): no card for
                    // them, not a «скоро» at the top.
                    if reason == .traffic && store.subscription != nil && !(purchases.loaded && purchases.packs.isEmpty) {
                        packsCard
                    }
                    subscriptionsCard
                    if showsOther { other }
                }
                .padding(16)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .animation(.easeInOut(duration: 0.2), value: note)
                // The App Store's answer: the cards come in (and an empty packs card goes) gently.
                .animation(.easeInOut(duration: 0.2), value: purchases.loaded)
            }
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle("Тарифы")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.scaled(13, weight: .bold))
                            .foregroundStyle(.secondary)
                            .frame(width: 32, height: 32)
                            .background(Palette.track, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Закрыть")
                }
            }
        }
        // A purchase under way isn't dropped by a pull down.
        .interactiveDismissDisabled(buying != nil || restoring)
        .task { await purchases.loadProducts() }
    }

    private var header: some View {
        VStack(spacing: 8) {
            Image(systemName: "crown.fill")
                .font(.scaled(26, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 60, height: 60)
                .background(Palette.accentGradient, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: Palette.primary.opacity(0.35), radius: 12, y: 6)
                .accessibilityHidden(true)
            Text("Titan VPS").font(.scaled(24, weight: .heavy))
            Text(reasonText.0).font(.scaled(14, weight: .medium)).foregroundStyle(reasonText.1)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
        .frame(maxWidth: .infinity)
    }

    private var reasonText: (String, Color) {
        let info = store.subscription?.info
        switch reason {
        case .expired:
            return ("Подписка истекла — продлите её, чтобы VPN снова работал", .red)
        case .traffic:
            guard let info, info.total > 0 else { return ("Трафик на обходах", .secondary) }
            return ("На обходах осталось \(formatBytes(info.remaining)) из \(formatBytes(info.total))",
                    info.remaining * 10 < info.total ? .orange : .secondary)
        case .renew:
            guard let info, info.expire > 0 else { return ("Продление подписки", .secondary) }
            let active = info.expire > Int64(Date().timeIntervalSince1970)
            return (active ? "Подписка активна до " + Fmt.date(info.expire) : "Подписка истекла", active ? .secondary : .red)
        case .start:
            return ("Подписка через App Store — ключ появится в приложении сам", .secondary)
        }
    }

    /// The App Store's subscriptions: the first and main way.
    private var subscriptionsCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                cardTitle("checkmark.seal.fill", Palette.primary, "Подписка через App Store",
                          "Продлевается сама, отменить можно в любой момент")
                // What a plan gives (App Review 3.1.2(c)); the rest is in each one's description.
                Text("Подписка открывает все серверы Titan VPS с безлимитным трафиком на обычных серверах. Лимит устройств и гигабайты на обходах — как в описании тарифа.")
                    .font(.scaled(12)).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                products(purchases.subscriptions, picked: $picked, empty: "Тарифы App Store скоро появятся")
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) { appleButtons }
                    VStack(spacing: 10) { appleButtons }
                }
                // Codes are the App Store's offer codes; what they give goes to a key.
                if store.subscription != nil && !purchases.subscriptions.isEmpty {
                    Button { Task { await purchases.redeemCode() } } label: {
                        Label("Ввести код предложения", systemImage: "ticket.fill").lineLimit(1).minimumScaleFactor(0.8)
                    }
                    .buttonStyle(SecondaryButtonStyle(height: 44, tint: .secondary))
                    .disabled(buying != nil || restoring)
                }
                if !purchases.subscriptions.isEmpty { terms }
                // Always: the privacy policy stays a tap away without products too.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 14) { legalLinks }
                    VStack(alignment: .leading, spacing: 6) { legalLinks }
                }
                .font(.scaled(12, weight: .semibold))
            }
            .padding(18)
        }
    }

    /// Гигабайты для обходов (consumables), for «Добавить ГБ».
    private var packsCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                cardTitle("chart.pie.fill", Palette.violet, "Гигабайты для обходов",
                          "Разовая покупка: добавятся к трафику на обходах")
                products(purchases.packs, picked: $pickedPack, empty: "Пакеты гигабайт в App Store скоро появятся")
            }
            .padding(18)
        }
    }

    private func cardTitle(_ icon: String, _ color: Color, _ title: String, _ subtitle: String) -> some View {
        HStack(spacing: 12) {
            IconTile(icon: icon, color: color, size: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.scaled(17, weight: .bold))
                Text(subtitle)
                    .font(.scaled(12)).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// The products as cards and Оплатить через Apple; while they load, their shapes; a load
    /// that failed, Повторить; none in the App Store (its answer), [empty].
    @ViewBuilder private func products(_ list: [Product], picked: Binding<String?>, empty: String) -> some View {
        if !list.isEmpty {
            let selected = list.first { $0.id == picked.wrappedValue } ?? list.first
            ForEach(list) { p in
                productRow(p, on: selected?.id == p.id) { picked.wrappedValue = p.id }
            }
            Button { buy(selected) } label: {
                if let buying, list.contains(where: { $0.id == buying }) {
                    ProgressView().tint(.white)
                } else {
                    Text("Оплатить через Apple").lineLimit(1).minimumScaleFactor(0.8)
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(buying != nil || restoring || selected == nil)
            .padding(.top, 2)
        } else if purchases.loading || !(purchases.loaded || purchases.loadFailed) {
            // The shapes of the cards to come, with a shine.
            VStack(spacing: 10) {
                ForEach(0..<3, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.track).frame(height: 62)
                }
            }
            .shimmer(tint: Palette.outline, duration: 1.3)
            .accessibilityLabel("Загрузка")
        } else if purchases.loadFailed {
            // No network or no App Store: not «скоро», and another try right here.
            VStack(spacing: 10) {
                HStack(spacing: 10) {
                    Image(systemName: "wifi.exclamationmark").foregroundStyle(.orange)
                    Text("Не удалось загрузить тарифы App Store").foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .font(.scaled(15, weight: .medium))
                Button { Task { await purchases.loadProducts() } } label: {
                    Label("Повторить", systemImage: "arrow.clockwise")
                }
                .buttonStyle(SecondaryButtonStyle(height: 44))
            }
            .padding(12)
            .frame(maxWidth: .infinity)
            .background(Palette.track.opacity(0.55), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        } else {
            HStack(spacing: 10) {
                Image(systemName: "clock").foregroundStyle(.secondary)
                Text(empty).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .font(.scaled(15, weight: .medium))
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Palette.track.opacity(0.55), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    @ViewBuilder private var appleButtons: some View {
        Button(action: restore) {
            if restoring { ProgressView() } else { Text("Восстановить покупки").lineLimit(1).minimumScaleFactor(0.8) }
        }
        .buttonStyle(SecondaryButtonStyle(height: 44))
        .disabled(buying != nil || restoring)
        Button { Task { await purchases.manageSubscriptions() } } label: {
            Text("Управление подпиской").lineLimit(1).minimumScaleFactor(0.8)
        }
        .buttonStyle(SecondaryButtonStyle(height: 44))
    }

    /// What App Review asks next to an auto-renewable subscription.
    private var terms: some View {
        Text("Подписка продлевается автоматически, пока вы её не отмените. Оплата списывается с Apple ID при подтверждении покупки и затем в течение 24 часов до конца каждого периода, если автопродление не отключено хотя бы за 24 часа до его конца. Отключить автопродление можно в настройках Apple ID («Управление подпиской»).")
            .font(.scaled(11)).foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder private var legalLinks: some View {
        Button("Условия использования") { openURL(Config.terms) }
        Button("Политика конфиденциальности") { openURL(Config.privacyPolicy) }
    }

    private func productRow(_ p: Product, on: Bool, pick: @escaping () -> Void) -> some View {
        Button {
            guard !on else { return }
            Haptics.selection()
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { pick() }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().stroke(on ? Palette.primary : Palette.outline, lineWidth: 2).frame(width: 22, height: 22)
                    if on { Circle().fill(Palette.primary).frame(width: 12, height: 12).transition(.scale) }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(p.displayName.isEmpty ? p.periodName : p.displayName)
                        .font(.scaled(16, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                    if p.subscription != nil {
                        Text("Продлевается " + p.renewal).font(.scaled(12)).foregroundStyle(.secondary)
                    }
                    // What it gives, as App Store Connect has it.
                    if !p.description.isEmpty {
                        Text(p.description).font(.scaled(12)).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                Text(p.displayPrice)
                    .font(.scaled(18, weight: .heavy)).monospacedDigit()
                    .foregroundStyle(on ? Palette.primary : Color.primary)
                    .lineLimit(1)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(minHeight: 62)
            .background(on ? Palette.primary.opacity(0.1) : Palette.track.opacity(0.55),
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? Palette.primary : Color.clear, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle(scale: 0.98))
        .disabled(buying != nil)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    /// Другие методы with a key: in every storefront as the owner chose
    /// (AppStorePurchases.otherMethods), and while the App Store has no subscription to sell, so
    /// an expired key is never left with nothing to tap. Before a key it would be the bot, as
    /// «Где взять ключ» on the key entry: the United States storefront only.
    private var showsOther: Bool {
        guard store.subscription != nil else { return purchases.linksOut }
        return purchases.otherMethods || purchases.noSubscriptions
    }

    /// Другие методы: the site's cabinet (signed in by the subscription's link) in the browser,
    /// where a bank's own certificates work; the bot if the subscription has no link.
    private var other: some View {
        VStack(spacing: 6) {
            Button(action: openOther) {
                Label("Другие методы", systemImage: "arrow.up.right.square")
            }
            .buttonStyle(SecondaryButtonStyle(height: 48, tint: .secondary))
            Text("Откроется в браузере").font(.scaled(12)).foregroundStyle(.tertiary)
        }
        .padding(.top, 4)
    }

    private func banner(_ n: Note) -> some View {
        let color: Color = n.warning ? .orange : Palette.primary
        return HStack(alignment: .top, spacing: 10) {
            Image(systemName: n.warning ? "exclamationmark.triangle.fill" : "info.circle.fill").foregroundStyle(color)
            Text(n.text).font(.scaled(14, weight: .medium)).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func buy(_ product: Product?) {
        guard let product, buying == nil else { return }
        let sub = store.subscription
        let pack = ProductIDs.packs.contains(product.id)
        guard sub != nil || !pack else { return }
        Haptics.tap(.medium)
        buying = product.id
        note = nil
        Task {
            let outcome = await purchases.purchase(product, for: sub)
            buying = nil
            show(outcome, keyless: sub == nil, pack: pack)
        }
    }

    private func restore() {
        guard !restoring else { return }
        let keyless = store.subscription == nil
        restoring = true
        note = nil
        Task {
            let outcome = await purchases.restore()
            restoring = false
            show(outcome, restored: true, keyless: keyless)
        }
    }

    private func show(_ outcome: AppStorePurchases.Outcome, restored: Bool = false, keyless: Bool, pack: Bool = false) {
        guard let n = outcome.note(restored: restored, keyless: keyless, pack: pack) else { return }
        if case .delivered = outcome {
            store.showToast(n.text, .success)
            // Without a key it has just signed in with the new one, fresh already.
            if !keyless { Task { await store.refresh(quiet: true) } }
            dismiss()
            return
        }
        if n.warning { Haptics.warning() } else if case .held = outcome { Haptics.success() }
        note = Note(text: n.text, warning: n.warning)
        // The note is at the top, away from the button VoiceOver is on.
        VoiceOver.announce(n.text)
    }

    private func openOther() {
        Haptics.tap()
        let site = store.subscription?.info.webPageUrl.flatMap(URL.init(string:)).flatMap { $0.scheme?.lowercased() == "https" ? $0 : nil }
        UIApplication.shared.open(site ?? Config.telegram)
    }
}
