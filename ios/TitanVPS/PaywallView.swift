// Payments, renewal and Личный кабинет (docs/HANDOFF.md): this file is compiled only with
// TITAN_PAYMENTS (set for the app in ios/project.yml); the panel's config can hide them
// (RemoteConfig.payments).
#if TITAN_PAYMENTS
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
/// Подписка's «Продлить подписку», the «Добавить ГБ» buttons and the key entry. The panel's
/// config may leave out either part (payments.appStore, payments.otherMethods), adds products,
/// orders them and gives them badges and notes (payments.products), and rewords its title and
/// Другие методы (texts); asked again as it opens, it closes when the config leaves no way to pay.
/// Design C: the app's cards (Card), the picked row on the brand's tint as Маршрут's cards,
/// RouteTone's ink.
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
                    if appStore && reason == .traffic && store.subscription != nil && !(purchases.loaded && purchases.packs.isEmpty) {
                        packsCard
                    }
                    if appStore { subscriptionsCard }
                    if showsOther { other }
                    if !appStore && !showsOther { nothingToPay }
                    // The privacy policy a tap away without the App Store's card too.
                    if !appStore {
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 14) { legalLinks }
                            VStack(spacing: 6) { legalLinks }
                        }
                        .font(.scaled(12, weight: .semibold))
                        .tint(RouteTone.accentInk)
                    }
                }
                .padding(16)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .animation(.easeInOut(duration: 0.2), value: note)
                // The App Store's answer: the cards come in (and an empty packs card goes) gently.
                .animation(.easeInOut(duration: 0.2), value: purchases.loaded)
            }
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle(store.remote.text(.tariffsTitle))
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
        .task {
            // The panel's config again (payments turned off a moment ago: closed below).
            store.refreshRemote(force: true)
            if appStore { await purchases.loadProducts() }
        }
        // No way to pay left in the config (payments, appStore and otherMethods): down, with a
        // word why. A purchase or a restore under way ends here first, its outcome shown.
        .onChange(of: store.remote.renewOn) { on in
            guard !on, buying == nil, !restoring else { return }
            store.showToast("Оплата сейчас недоступна", .warning)
            dismiss()
        }
    }

    /// The App Store's part: on unless the panel's config turned it off (payments.appStore).
    private var appStore: Bool { store.remote.appStoreOn }

    private var header: some View {
        VStack(spacing: 8) {
            Image(systemName: "ticket.fill")
                .font(.scaled(26, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 60, height: 60)
                .background(Palette.accentGradient, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: Palette.primary.opacity(0.35), radius: 12, y: 6)
                .accessibilityHidden(true)
            Text("Titan").font(.scaled(24, weight: .heavy))
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
            return ("Проездной закончился — продлите его, чтобы снова поехать", RouteTone.danger)
        case .traffic:
            guard let info, info.total > 0 else { return ("Гигабайты на объездах", .secondary) }
            return ("Объезды: осталось \(formatBytes(info.remaining)) из \(formatBytes(info.total))",
                    info.remaining * 10 < info.total ? RouteTone.warn : .secondary)
        case .renew:
            guard let info, info.expire > 0 else { return ("Продление проездного", .secondary) }
            let active = info.expire > Int64(Date().timeIntervalSince1970)
            return (active ? "Проездной действует до " + Fmt.date(info.expire) : "Проездной закончился", active ? .secondary : RouteTone.danger)
        case .start:
            return ("Проездной через App Store — его код появится в приложении сам", .secondary)
        }
    }

    /// The App Store's subscriptions: the first and main way.
    private var subscriptionsCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                cardTitle("checkmark.seal.fill", Palette.primary, "Проездной через App Store",
                          "Продлевается сам, отменить можно в любой момент")
                // What a plan gives (App Review 3.1.2(c)); the rest is in each one's description.
                Text("Проездной открывает все направления Titan, по обычным — без ограничений. Лимит устройств и гигабайты на объездах — как в описании тарифа.")
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
                .tint(RouteTone.accentInk)
            }
            .padding(18)
        }
    }

    /// Гигабайты для объездов (consumables), for «Добавить ГБ».
    private var packsCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                cardTitle("chart.pie.fill", Palette.violet, "Гигабайты для объездов",
                          "Разовая покупка: добавятся к гигабайтам на объездах")
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
                    Image(systemName: "wifi.exclamationmark").foregroundStyle(RouteTone.warn)
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
            .background(RouteTone.well, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        } else {
            HStack(spacing: 10) {
                Image(systemName: "clock").foregroundStyle(.secondary)
                Text(empty).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .font(.scaled(15, weight: .medium))
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(RouteTone.well, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    @ViewBuilder private var appleButtons: some View {
        Button(action: restore) {
            if restoring { ProgressView() } else { Text("Восстановить покупки").lineLimit(1).minimumScaleFactor(0.8) }
        }
        .buttonStyle(SecondaryButtonStyle(height: 44))
        .disabled(buying != nil || restoring)
        Button { Task { await purchases.manageSubscriptions() } } label: {
            Text("Автопродление в App Store").lineLimit(1).minimumScaleFactor(0.8)
        }
        .buttonStyle(SecondaryButtonStyle(height: 44))
    }

    /// What App Review asks next to an auto-renewable subscription.
    private var terms: some View {
        Text("Проездной продлевается автоматически, пока вы не отмените автопродление. Оплата списывается с Apple ID при подтверждении покупки и затем в течение 24 часов до конца каждого периода, если автопродление не отменено хотя бы за 24 часа до его конца. Отменить автопродление можно в настройках Apple ID.")
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
                    let info = store.remote.product(p.id)
                    HStack(spacing: 6) {
                        Text(p.displayName.isEmpty ? p.periodName : p.displayName)
                            .font(.scaled(16, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                        if let badge = info?.badge { badgePill(badge, on: on) }
                    }
                    // The panel's word on it (payments.products' note).
                    if let note = info?.note {
                        Text(note).font(.scaled(12, weight: .semibold))
                            .foregroundStyle(on ? RouteTone.accentInkOnTint : RouteTone.accentInk)
                            .fixedSize(horizontal: false, vertical: true)
                    }
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
                    .foregroundStyle(on ? RouteTone.accentInkOnTint : Color.primary)
                    .lineLimit(1)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(minHeight: 62)
            // As Маршрут's cards: the picked one on the brand's tint with its edge.
            .background(on ? RouteTone.picked : RouteTone.well,
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(on ? Palette.primary.opacity(0.5) : Color.clear, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle(scale: 0.98))
        .disabled(buying != nil)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    /// The panel's badge on a product («Выгодно»): a small pill as Проверить дороги, solid on the
    /// picked row.
    private func badgePill(_ text: String, on: Bool) -> some View {
        Text(text)
            .font(.scaled(11, weight: .bold))
            .lineLimit(1)
            .foregroundStyle(on ? Color.white : RouteTone.accentInkOnTint)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(on ? AnyShapeStyle(Palette.accentGradient) : AnyShapeStyle(Palette.primary.opacity(0.12)), in: Capsule())
            .fixedSize()
    }

    /// Другие методы with a key: in every storefront as the owner chose
    /// (AppStorePurchases.otherMethods), and while the App Store has no subscription to sell (or
    /// its part is turned off), so an expired key is never left with nothing to tap, unless the
    /// panel's config turned them off. Before a key it is the bot, as «Где взять ключ» on the key
    /// entry (AppStorePurchases.linksOut).
    private var showsOther: Bool {
        guard store.subscription != nil else { return purchases.linksOut }
        return purchases.otherMethods
            || ((purchases.noSubscriptions || !appStore) && store.remote.otherMethodsOn)
    }

    /// Neither part on (the panel's config): where to ask instead.
    private var nothingToPay: some View {
        VStack(spacing: 10) {
            Text("Сейчас продлить проездной в приложении нельзя — напишите в поддержку")
                .font(.scaled(15, weight: .medium)).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button { openURL(store.supportURL) } label: {
                Label("Поддержка", systemImage: "headphones")
            }
            .buttonStyle(SecondaryButtonStyle(height: 46))
        }
        .padding(18)
        .frame(maxWidth: .infinity)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: Palette.cardRadius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Palette.cardRadius, style: .continuous).strokeBorder(Palette.edge, lineWidth: 0.5))
    }

    /// Другие методы: the site's cabinet (signed in by the subscription's link) in the browser,
    /// where a bank's own certificates work; the bot if the subscription has no link.
    private var other: some View {
        VStack(spacing: 6) {
            Button(action: openOther) {
                Label(store.remote.text(.tariffsOther), systemImage: "arrow.up.right.square")
            }
            .buttonStyle(SecondaryButtonStyle(height: 48, tint: .secondary))
            Text("Откроется в браузере").font(.scaled(12)).foregroundStyle(.tertiary)
        }
        .padding(.top, 4)
    }

    private func banner(_ n: Note) -> some View {
        let color: Color = n.warning ? RouteTone.warn : RouteTone.accentInk
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
        // By type, as the cards are split: a pack the config added too.
        let pack = product.type == .consumable
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
#endif
