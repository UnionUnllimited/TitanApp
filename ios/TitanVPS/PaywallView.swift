import StoreKit
import SwiftUI

/// Why Тарифы opened: the line under its title says so.
enum PaywallReason: String, Identifiable {
    case renew, expired, traffic
    var id: String { rawValue }
}

/// Тарифы: the App Store's subscriptions first (Оплатить через Apple, Восстановить покупки,
/// Управление подпиской, the auto-renewal terms), then Другие методы: the site's cabinet in
/// the browser, without a price beside it. Opened from Главная's «Подписка истекла»,
/// Подписка's «Продлить подписку» and the «Добавить ГБ» buttons.
struct PaywallView: View {
    let reason: PaywallReason
    @EnvironmentObject var store: AppStore
    @ObservedObject private var purchases = AppStorePurchases.shared
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var picked: String?
    @State private var buying = false
    @State private var restoring = false
    @State private var note: Note?

    private struct Note: Equatable {
        let text: String
        var warning = false
    }

    private var selected: Product? {
        purchases.products.first { $0.id == picked } ?? purchases.products.first
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    header
                    if let note {
                        banner(note).transition(.opacity.combined(with: .move(edge: .top)))
                    }
                    apple
                    other
                }
                .padding(16)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .animation(.easeInOut(duration: 0.2), value: note)
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
        .interactiveDismissDisabled(buying || restoring)
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
        }
    }

    /// The App Store's subscriptions: the first and main way.
    private var apple: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    IconTile(icon: "checkmark.seal.fill", color: Palette.primary, size: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Подписка через App Store").font(.scaled(17, weight: .bold))
                        Text("Продлевается сама, отменить можно в любой момент")
                            .font(.scaled(12)).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if !purchases.loaded {
                    // The shapes of the cards to come, with a shine.
                    VStack(spacing: 10) {
                        ForEach(0..<3, id: \.self) { _ in
                            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.track).frame(height: 62)
                        }
                    }
                    .shimmer(tint: Palette.outline, duration: 1.3)
                    .accessibilityLabel("Загрузка")
                } else if purchases.products.isEmpty {
                    HStack(spacing: 10) {
                        Image(systemName: "clock").foregroundStyle(.secondary)
                        Text("Тарифы App Store скоро появятся").foregroundStyle(.secondary)
                    }
                    .font(.scaled(15, weight: .medium))
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(Palette.track.opacity(0.55), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                } else {
                    ForEach(purchases.products) { productRow($0) }
                    Button(action: buy) {
                        if buying {
                            ProgressView().tint(.white)
                        } else {
                            Text("Оплатить через Apple").lineLimit(1).minimumScaleFactor(0.8)
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(buying || restoring || selected == nil || store.subscription == nil)
                    .padding(.top, 2)
                }
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) { appleButtons }
                    VStack(spacing: 10) { appleButtons }
                }
                if !purchases.products.isEmpty { terms }
            }
            .padding(18)
        }
    }

    @ViewBuilder private var appleButtons: some View {
        Button(action: restore) {
            if restoring { ProgressView() } else { Text("Восстановить покупки").lineLimit(1).minimumScaleFactor(0.8) }
        }
        .buttonStyle(SecondaryButtonStyle(height: 44))
        .disabled(buying || restoring)
        Button(action: manage) {
            Text("Управление подпиской").lineLimit(1).minimumScaleFactor(0.8)
        }
        .buttonStyle(SecondaryButtonStyle(height: 44))
    }

    /// What App Review asks next to an auto-renewable subscription.
    private var terms: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Подписка продлевается автоматически, пока вы её не отмените. Оплата списывается с Apple ID при подтверждении покупки и затем в течение 24 часов до конца каждого периода, если автопродление не отключено хотя бы за 24 часа до его конца. Отключить автопродление можно в настройках Apple ID («Управление подпиской»).")
                .font(.scaled(11)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            // Side by side where both fit, one under the other on a narrow phone.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) { legalLinks }
                VStack(alignment: .leading, spacing: 6) { legalLinks }
            }
            .font(.scaled(12, weight: .semibold))
        }
    }

    @ViewBuilder private var legalLinks: some View {
        Button("Условия использования") { openURL(Config.terms) }
        Button("Политика конфиденциальности") { openURL(Config.privacyPolicy) }
    }

    private func productRow(_ p: Product) -> some View {
        let on = selected?.id == p.id
        return Button {
            guard !on else { return }
            Haptics.selection()
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { picked = p.id }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().stroke(on ? Palette.primary : Palette.outline, lineWidth: 2).frame(width: 22, height: 22)
                    if on { Circle().fill(Palette.primary).frame(width: 12, height: 12).transition(.scale) }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(p.displayName.isEmpty ? p.periodName : p.displayName)
                        .font(.scaled(16, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                    Text("Продлевается " + p.renewal).font(.scaled(12)).foregroundStyle(.secondary)
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
        .disabled(buying)
        .accessibilityAddTraits(on ? .isSelected : [])
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

    private func buy() {
        guard let product = selected, let sub = store.subscription, !buying else { return }
        Haptics.tap(.medium)
        buying = true
        note = nil
        Task {
            let outcome = await purchases.purchase(product, for: sub)
            buying = false
            show(outcome)
        }
    }

    private func restore() {
        guard !restoring else { return }
        restoring = true
        note = nil
        Task {
            let outcome = await purchases.restore()
            restoring = false
            show(outcome, restored: true)
        }
    }

    private func show(_ outcome: AppStorePurchases.Outcome, restored: Bool = false) {
        switch outcome {
        case .delivered:
            store.showToast(restored ? "Покупки восстановлены" : "Подписка продлена", .success)
            Task { await store.refresh(quiet: true) }
            dismiss()
        case .held:
            Haptics.success()
            note = Note(text: "Покупка получена — подписка продлится после проверки")
        case .pending:
            note = Note(text: "Покупка ждёт подтверждения — подписка продлится, когда App Store её одобрит")
        case .nothing:
            note = Note(text: "Покупок в App Store не найдено")
        case .otherKey:
            note = Note(text: "Подписка App Store оформлена с другим ключом — напишите в поддержку", warning: true)
        case .cancelled:
            break
        case .failed(let why):
            Haptics.warning()
            note = Note(text: "Покупка не прошла: " + why, warning: true)
        }
    }

    /// StoreKit's own sheet over the app; the App Store's page if it doesn't open.
    private func manage() {
        Task {
            if let scene = AppStorePurchases.activeScene {
                do {
                    try await StoreKit.AppStore.showManageSubscriptions(in: scene)
                    return
                } catch {}
            }
            openURL(Config.manageSubscriptions)
        }
    }

    private func openOther() {
        Haptics.tap()
        let site = store.subscription?.info.webPageUrl.flatMap(URL.init(string:)).flatMap { $0.scheme?.lowercased() == "https" ? $0 : nil }
        UIApplication.shared.open(site ?? Config.telegram)
    }
}
