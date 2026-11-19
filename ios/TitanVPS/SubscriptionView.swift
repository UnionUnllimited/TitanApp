import SwiftUI

/// Проездной (the subscription tab): expiry, bypass traffic, «Продлить проездной» (Тарифы) and
/// a "Личный кабинет" button with what it has. Without TITAN_PAYMENTS (no payments, renewal or
/// cabinet in this build, docs/HANDOFF.md): expiry, bypass traffic, devices and support.
struct SubscriptionView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.openURL) private var openURL
    @State private var showDevices = false
    #if TITAN_PAYMENTS
    @ObservedObject private var purchases = AppStorePurchases.shared
    @State private var showCabinet = false
    /// Продлить подписку in the cabinet: Тарифы once its sheet is down.
    @State private var renewAfterCabinet = false
    #endif
    /// Bumped by a tap on Подписка in the tab bar while it is on screen: back to the top.
    var toTop = 0
    private static let top = "subscription.top"

    var body: some View {
        ScrollViewReader { proxy in
            page
                .onChange(of: toTop) { _ in
                    withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(Self.top, anchor: .top) }
                }
        }
    }

    private var page: some View {
        ScrollView {
            if let sub = store.subscription {
                let info = sub.info
                let now = Int64(Date().timeIntervalSince1970)
                let active = info.expire <= 0 || info.expire > now
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Проездной").font(.scaled(28, weight: .bold))
                        Spacer()
                        CircleIconButton(icon: "arrow.clockwise", spinning: store.busy, size: 42) {
                            Task { await store.refreshByHand() }
                        }
                        .disabled(store.busy)
                        .accessibilityLabel("Обновить проездной")
                    }
                    .padding(.top, 8)
                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 6) {
                                Circle().fill(active ? Palette.green : .red).frame(width: 8, height: 8)
                                Text(active ? "Проездной действует" : "Проездной закончился")
                                    .font(.scaled(13, weight: .semibold))
                            }
                            .foregroundStyle(active ? Palette.green : .red)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background((active ? Palette.green : Color.red).opacity(0.14), in: Capsule())
                            Text(expiry(info.expire)).font(.scaled(26, weight: .bold))
                            if info.expire > 0 {
                                Text("Осталось \(max((info.expire - now) / 86400, 0)) дн.").foregroundStyle(.secondary)
                            }
                            Text("Обновлено " + Fmt.ago(sub.fetchedAt)).font(.scaled(12)).foregroundStyle(.tertiary)
                        }
                        .padding(16)
                    }
                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(info.total > 0 ? "Объезды: осталось" : "Объезды").foregroundStyle(.secondary).font(.scaled(14))
                            if info.total > 0 {
                                Text("\(formatBytes(info.remaining)) из \(formatBytes(info.total))").font(.scaled(26, weight: .bold))
                                GradientBar(value: Double(info.remaining) / Double(max(info.total, 1)),
                                            gradient: info.remaining * 10 < info.total ? LinearGradient(colors: [.orange, .red], startPoint: .leading, endPoint: .trailing) : Palette.accentGradient)
                            } else {
                                Text("Безлимит").font(.scaled(26, weight: .bold))
                            }
                            Text("По обычным направлениям — безлимит").font(.scaled(13)).foregroundStyle(.secondary)
                            Text(bypassNote(info))
                                .font(.scaled(12)).foregroundStyle(.secondary).padding(.top, 4)
                            #if TITAN_PAYMENTS
                            if info.total > 0 {
                                // Тарифы (the App Store first); orange once less than 10 % is left.
                                let low = info.remaining * 10 < info.total
                                Button { store.paywall = .traffic } label: {
                                    Label("Добавить ГБ", systemImage: "plus.circle.fill").lineLimit(1)
                                }
                                .buttonStyle(SecondaryButtonStyle(height: 44, tint: low ? Color.orange : Palette.primary))
                                .padding(.top, 4)
                            }
                            #endif
                        }
                        .padding(16)
                    }
                    #if TITAN_PAYMENTS
                    Button { store.paywall = renewReason } label: {
                        Label("Продлить проездной", systemImage: "ticket.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    // Without the site's link, the bot instead: the United States storefront only,
                    // as it sells subscriptions too (App Review 3.1.1(a)).
                    if hasCabinet(info) {
                        Button {
                            if info.webPageUrl != nil { showCabinet = true } else { openURL(Config.telegram) }
                        } label: {
                            Label("Личный кабинет", systemImage: "person.crop.circle.fill")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                    }
                    #endif
                    if let acc = store.account, acc.devicesEnabled {
                        Card {
                            Button { showDevices = true } label: {
                                SettingsRow(icon: "iphone.and.arrow.forward", color: Palette.primary, title: "Устройства", chevron: true) {
                                    Text("\(acc.devices.count)" + (acc.deviceLimit > 0 ? " из \(acc.deviceLimit)" : ""))
                                        .font(.scaled(15, weight: .medium)).foregroundStyle(.secondary)
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                        }
                    }
                    #if TITAN_PAYMENTS
                    // What the in-app cabinet has; the site's tariffs and codes as
                    // AppStorePurchases.otherMethods allows.
                    if hasCabinet(info) {
                        Card {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("В личном кабинете").fontWeight(.semibold)
                                feature("calendar", "Сколько дней осталось")
                                feature("chart.pie", "Трафик на объездах")
                                feature("person.text.rectangle", "Устройства и ваш ID")
                                if purchases.otherMethods { feature("tag", "Тарифы и коды на продление") }
                            }
                            .padding(16)
                        }
                    }
                    // What the in-app cabinet can't do: the bot, or (United States storefront
                    // only, App Review 3.1.1(a)) the full cabinet on the site, where it is sold too.
                    Card {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Расширенные функции").fontWeight(.semibold)
                            Text("Отвязка устройств, рефералы и другие настройки аккаунта — в боте" + (purchases.linksOut ? " или в полном кабинете на сайте (вход по почте или через Telegram)." : "."))
                                .font(.scaled(13)).foregroundStyle(.secondary)
                            let site = info.webPageUrl.flatMap(URL.init(string:)).flatMap({ u -> URL? in
                                guard purchases.linksOut, let scheme = u.scheme, let host = u.host else { return nil }
                                return URL(string: "\(scheme)://\(host)/auth")
                            })
                            // Side by side where both fit, one under the other on a narrow phone.
                            ViewThatFits(in: .horizontal) {
                                HStack(spacing: 10) { linkButtons(site) }
                                VStack(spacing: 10) { linkButtons(site) }
                            }
                        }
                        .padding(16)
                    }
                    #else
                    // Without TITAN_PAYMENTS neither the bot nor the site, where it is sold, as such:
                    // support only, for what the app can't do for the account (unlinking a device).
                    Card {
                        Button { openURL(store.supportURL) } label: {
                            SettingsRow(icon: "headphones", color: Palette.primary, title: "Поддержка", subtitle: "Отвязка устройств и вопросы по аккаунту") {
                                Image(systemName: "arrow.up.right").font(.scaled(13, weight: .semibold)).foregroundStyle(.tertiary)
                            }
                        }
                        .buttonStyle(RowButtonStyle())
                    }
                    #endif
                }
                .padding(16)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
                .id(Self.top)
            }
        }
        .background(Palette.background.ignoresSafeArea())
        .refreshable { await store.refreshFromPull() }
        .task { await store.loadAccount() }
        #if TITAN_PAYMENTS
        // Тарифы from a reminder (RootView): this tab's sheets go down first.
        .onChange(of: store.closeSheets) { _ in
            showCabinet = false
            showDevices = false
        }
        #endif
        .sheet(isPresented: $showDevices) {
            if let acc = store.account {
                #if TITAN_PAYMENTS
                DevicesView(account: acc)
                #else
                DevicesView(account: acc, support: store.supportURL)
                #endif
            }
        }
        #if TITAN_PAYMENTS
        // One sheet at a time: Тарифы (RootView's) once the cabinet is down.
        .sheet(isPresented: $showCabinet, onDismiss: {
            guard renewAfterCabinet else { return }
            renewAfterCabinet = false
            store.paywall = renewReason
        }) {
            if let link = store.subscription?.info.webPageUrl {
                CabinetView(link: link, devices: store.account.map { ($0.devices.count, $0.deviceLimit) }) {
                    renewAfterCabinet = true
                    showCabinet = false
                }
                .environmentObject(store)
            }
        }
        #endif
    }

    /// Объезды explained; with TITAN_PAYMENTS, where more ГБ come from.
    private func bypassNote(_ info: SubscriptionInfo) -> String {
        let note = "Объезды — направления для мобильного интернета, когда оператор пропускает только разрешённые сайты (белые списки) и обычные направления не работают. Гигабайты списываются, только пока вы едете по объезду. На Wi-Fi объезды не нужны и недоступны."
        #if TITAN_PAYMENTS
        return note + (info.total > 0 ? " Закончились ГБ — нажмите «Добавить ГБ»." : "")
        #else
        return note
        #endif
    }

    #if TITAN_PAYMENTS
    /// Личный кабинет: in the app with the site's link; without it the bot, as linksOut allows.
    private func hasCabinet(_ info: SubscriptionInfo) -> Bool {
        info.webPageUrl != nil || purchases.linksOut
    }

    /// Продлить подписку: Тарифы, saying whether it is still running.
    private var renewReason: PaywallReason {
        let expire = store.subscription?.info.expire ?? 0
        return expire <= 0 || expire > Int64(Date().timeIntervalSince1970) ? .renew : .expired
    }

    @ViewBuilder private func linkButtons(_ site: URL?) -> some View {
        Button { openURL(Config.telegram) } label: {
            Label("Открыть бота", systemImage: "paperplane.fill").lineLimit(1)
        }
        .buttonStyle(SecondaryButtonStyle(height: 46))
        if let site {
            Button { openURL(site) } label: {
                Label("Открыть сайт", systemImage: "globe").lineLimit(1)
            }
            .buttonStyle(SecondaryButtonStyle(height: 46))
        }
    }

    private func feature(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(Palette.primary).frame(width: 22)
            Text(text)
        }
    }
    #endif

    private func expiry(_ expire: Int64) -> String {
        guard expire > 0 else { return "Бессрочно" }
        return "до " + Fmt.date(expire)
    }
}

/// The devices that used the key, and the limit (from the bot's /api/sub/…/info).
struct DevicesView: View {
    let account: AccountAPI.Info
    #if !TITAN_PAYMENTS
    /// Отвязать устройство: support, not the bot where it is sold (docs/HANDOFF.md).
    let support: URL
    #endif
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Text("Привязано")
                        Spacer()
                        Text("\(account.devices.count)" + (account.deviceLimit > 0 ? " из \(account.deviceLimit)" : ""))
                            .foregroundStyle(.secondary)
                    }
                }
                Section {
                    if account.devices.isEmpty {
                        Text("Пока ни одного устройства").foregroundStyle(.secondary)
                    }
                    ForEach(account.devices) { d in
                        HStack(spacing: 12) {
                            Image(systemName: icon(d.os)).foregroundStyle(Palette.primary).frame(width: 26)
                            VStack(alignment: .leading, spacing: 2) {
                                Text((d.model.isEmpty ? "Устройство" : d.model) + (isThis(d) ? " · это устройство" : ""))
                                    .font(.scaled(15))
                                Text([d.os + (d.osVersion.isEmpty ? "" : " \(d.osVersion)"), d.app.isEmpty ? "" : AccountAPI.appName(d.app)]
                                    .filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.scaled(12)).foregroundStyle(.secondary)
                                if let seen = Self.lastSeen(d.lastSeen) {
                                    Text(seen).font(.scaled(12)).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }
                } footer: {
                    #if TITAN_PAYMENTS
                    Text("Отвязать лишнее устройство можно в боте.")
                    #else
                    Text("Отвязать лишнее устройство поможет поддержка.")
                    #endif
                }
                Section {
                    #if TITAN_PAYMENTS
                    Button { openURL(Config.telegram) } label: { Label("Открыть бота", systemImage: "paperplane") }
                    #else
                    Button { openURL(support) } label: { Label("Поддержка", systemImage: "headphones") }
                    #endif
                }
            }
            .navigationTitle("Устройства")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
            }
        }
    }

    /// Ours, from this phone (same rule as Android: our app and the same model).
    private func isThis(_ d: AccountAPI.Device) -> Bool {
        let model = SubscriptionAPI.deviceModel()
        return AccountAPI.appName(d.app).hasPrefix("Titan VPS") && !d.model.isEmpty && !model.isEmpty
            && (d.model.localizedCaseInsensitiveContains(model) || model.localizedCaseInsensitiveContains(d.model))
    }

    /// "Активность: 5 мин назад" from the bot's time (ISO 8601, or "yyyy-MM-dd HH:mm:ss" in UTC).
    static func lastSeen(_ text: String) -> String? {
        guard !text.isEmpty else { return nil }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var date = iso.date(from: text)
        if date == nil { iso.formatOptions = [.withInternetDateTime]; date = iso.date(from: text) }
        if date == nil {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = TimeZone(identifier: "UTC")
            for format in ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS"] where date == nil {
                f.dateFormat = format
                date = f.date(from: text)
            }
        }
        guard let date else { return nil }
        let min = max(Int(Date().timeIntervalSince(date) / 60), 0)
        let ago = min < 2 ? "только что" : min < 60 ? "\(min) мин назад" : min < 60 * 24 ? "\(min / 60) ч назад" : "\(min / 60 / 24) дн. назад"
        return "Активность: " + ago
    }

    private func icon(_ os: String) -> String {
        let o = os.lowercased()
        if o.contains("ios") || o.contains("iphone") { return "iphone" }
        if o.contains("android") { return "smartphone" }
        return "desktopcomputer"
    }
}

struct ProfileView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var vpn: VPNController
    @Environment(\.openURL) private var openURL
    @AppStorage("theme") private var theme = "system"
    @AppStorage("autoconnect") private var autoconnect = false
    @AppStorage(VPNController.keepAliveKey) private var keepAlive = true
    @AppStorage(Reminders.key) private var reminders = true
    @AppStorage(LiveActivityController.key) private var liveActivity = true
    /// Live Activities are off for the app in iOS Settings: the switch alone shows nothing.
    @State private var activitiesOff = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmLogout = false
    @State private var confirmDelete = false
    #if TITAN_PAYMENTS
    /// Удалить аккаунт found an App Store subscription: Apple keeps billing it until cancelled.
    @State private var appleSubscribed = false
    #endif
    @State private var deleting = false
    @State private var confirmReset = false
    @State private var about = false
    /// Технический режим: 7 taps on the version (under 3 s apart) ask for the code.
    @AppStorage(TechMode.key) private var techMode = false
    @State private var taps = 0
    @State private var lastTap = Date.distantPast
    @State private var askCode = false
    @State private var code = ""
    @State private var wrongCode = false
    @State private var showTech = false
    var openSubscription: () -> Void = {}
    var openHome: () -> Void = {}
    /// Bumped by a tap on Профиль in the tab bar while it is on screen: back to the top.
    var toTop = 0
    /// A swipe across this screen to another tab (TabSwipe): on the root of the
    /// NavigationStack, so Технический режим pushed over it has none.
    var swipeTab: (Int) -> Bool = { _ in false }
    private static let top = "profile.top"

    private var version: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "") (\(info?["CFBundleVersion"] as? String ?? ""))"
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        if let sub = store.subscription { accountCard(sub) }

                        SectionTitle("Оформление")
                        Card { ThemePicker(theme: $theme) }

                        SectionTitle("Поездка")
                        Card {
                            Button {
                                Haptics.selection()
                                openHome()
                            } label: {
                                SettingsRow(icon: "signpost.right.fill", color: Palette.primary, title: "Направление", chevron: true) {
                                    HStack(spacing: 6) {
                                        if let s = store.selected { FlagCircle(server: s.name, size: 20) }
                                        Text(serverName).font(.scaled(15)).foregroundStyle(.secondary).lineLimit(1)
                                    }
                                    .frame(maxWidth: 170, alignment: .trailing)
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                            RowDivider()
                            Toggle(isOn: $autoconnect) {
                                HStack(spacing: 14) {
                                    IconTile(icon: "car.fill", color: Palette.green)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Сразу в путь").font(.scaled(16, weight: .medium))
                                            .lineLimit(1).minimumScaleFactor(0.75)
                                        Text("Поедем сами, как только откроете приложение")
                                            .font(.scaled(12)).foregroundStyle(.secondary)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                }
                            }
                            .toggleStyle(TitanToggleStyle())
                            .padding(.horizontal, 16).padding(.vertical, 12)
                            RowDivider()
                            // VPN On Demand: on by default, applies to the running VPN at once.
                            Toggle(isOn: $keepAlive) {
                                HStack(spacing: 14) {
                                    IconTile(icon: "arrow.triangle.2.circlepath", color: Palette.green)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Всегда в пути").font(.scaled(16, weight: .medium))
                                            .lineLimit(1).minimumScaleFactor(0.75)
                                        Text(keepAliveNote)
                                            .font(.scaled(12)).foregroundStyle(.secondary)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                }
                            }
                            .toggleStyle(TitanToggleStyle())
                            .padding(.horizontal, 16).padding(.vertical, 12)
                            // With the servers this build parsed: turned on while connected, the profile
                            // gets the current config too (no TFO on regular servers), not the old one.
                            .onChange(of: keepAlive) { on in
                                vpn.setKeepAlive(on, rebuilding: store.subscription.flatMap { $0.parsedNow ? $0.servers : nil } ?? [])
                            }
                            RowDivider()
                            Toggle(isOn: $reminders) {
                                HStack(spacing: 14) {
                                    IconTile(icon: "bell.badge.fill", color: .orange)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Напоминания").font(.scaled(16, weight: .medium))
                                            .lineLimit(1).minimumScaleFactor(0.75)
                                        Text("За 3 дня и за день до конца проездного и когда на объездах кончается трафик")
                                            .font(.scaled(12)).foregroundStyle(.secondary)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                }
                            }
                            .toggleStyle(TitanToggleStyle())
                            .padding(.horizontal, 16).padding(.vertical, 12)
                            .onChange(of: reminders) { on in
                                guard on else { Reminders.cancelAll(); return }
                                Task {
                                    guard await Reminders.allowed() else {
                                        store.showToast("Разрешите уведомления для Titan VPS в Настройках iOS", .warning)
                                        return
                                    }
                                    if let info = store.subscription?.info { await Reminders.update(for: info) }
                                }
                            }
                            // The Live Activity is drawn by TitanWidgets, which needs iOS 18.
                            if #available(iOS 18.0, *) {
                                RowDivider()
                                liveActivityRow
                            }
                        }

                        SectionTitle("Приложение")
                        Card {
                            Button { openURL(store.supportURL) } label: {
                                SettingsRow(icon: "headphones", color: Palette.primary, title: "Поддержка", subtitle: "Ответим в Telegram") {
                                    Image(systemName: "arrow.up.right").font(.scaled(13, weight: .semibold)).foregroundStyle(.tertiary)
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                            RowDivider()
                            Button { Task { await store.refreshByHand() } } label: {
                                SettingsRow(icon: "arrow.clockwise", color: Palette.green, title: "Обновить проездной",
                                            subtitle: store.subscription.map { "Обновлено " + Fmt.ago($0.fetchedAt) }) {
                                    if store.busy { ProgressView() }
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                            .disabled(store.busy)
                            RowDivider()
                            Button { about = true } label: {
                                SettingsRow(icon: "info.circle.fill", color: Palette.violet, title: "О приложении",
                                            subtitle: "Версия и открытый исходный код", chevron: true)
                            }
                            .buttonStyle(RowButtonStyle())
                            RowDivider()
                            Button { openURL(Config.privacyPolicy) } label: {
                                SettingsRow(icon: "hand.raised.fill", color: Palette.primary, title: "Политика конфиденциальности") {
                                    Image(systemName: "arrow.up.right").font(.scaled(13, weight: .semibold)).foregroundStyle(.tertiary)
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                            RowDivider()
                            SettingsRow(icon: "number", color: .gray, title: "Версия") {
                                Text(wrongCode ? "Неверный код" : version)
                                    .font(.scaled(15).monospacedDigit())
                                    .foregroundStyle(wrongCode ? Color.red : Color.secondary)
                            }
                            .onTapGesture { versionTapped() }
                            if techMode {
                                RowDivider()
                                Button { showTech = true } label: {
                                    SettingsRow(icon: "wrench.and.screwdriver.fill", color: .orange, title: "Технический режим", chevron: true)
                                }
                                .buttonStyle(RowButtonStyle())
                            }
                        }

                        Card {
                            Button { confirmReset = true } label: {
                                SettingsRow(icon: "arrow.counterclockwise", color: .orange, title: "Сброс настроек")
                            }
                            .buttonStyle(RowButtonStyle())
                        }
                        .padding(.top, 18)

                        SectionTitle("Аккаунт")
                        Card {
                            Button { confirmLogout = true } label: {
                                SettingsRow(icon: "rectangle.portrait.and.arrow.right", color: .red, title: "Выйти из аккаунта", titleColor: .red)
                            }
                            .buttonStyle(RowButtonStyle())
                            .disabled(deleting)
                            RowDivider()
                            Button { askDelete() } label: {
                                SettingsRow(icon: "trash.fill", color: .red, title: "Удалить аккаунт", titleColor: .red) {
                                    if deleting { ProgressView() }
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                            .disabled(deleting)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 24)
                    .frame(maxWidth: 640)
                    .frame(maxWidth: .infinity)
                    .id(Self.top)
                }
                // A tap on Профиль in the tab bar while on it: back from Технический режим first
                // (as the system bar pops), the next tap to the top.
                .onChange(of: toTop) { _ in
                    if showTech { showTech = false; return }
                    withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(Self.top, anchor: .top) }
                }
            }
            .background(Palette.background.ignoresSafeArea())
            .tabSwipe(swipeTab)
            // Back from iOS Settings: maybe allowed there now.
            .onAppear { activitiesOff = !LiveActivityController.allowedInSettings }
            .onChange(of: scenePhase) { if $0 == .active { activitiesOff = !LiveActivityController.allowedInSettings } }
            .navigationTitle("Настройки")
            // The system tab bar stays hidden here and on a pushed screen too (RootView's TitanTabBar).
            .toolbar(.hidden, for: .tabBar)
            .navigationDestination(isPresented: $showTech) { TechModeView().toolbar(.hidden, for: .tabBar) }
            .sheet(isPresented: $about) { AboutSheet(version: version, support: store.supportURL) }
            #if TITAN_PAYMENTS
            // Тарифы from a reminder (RootView): О приложении goes down first.
            .onChange(of: store.closeSheets) { _ in about = false }
            #endif
            .alert("Код", isPresented: $askCode) {
                SecureField("Код", text: $code).keyboardType(.numberPad)
                Button("Открыть") { enterCode() }
                Button("Отмена", role: .cancel) { code = "" }
            }
            .alert("Сбросить настройки?", isPresented: $confirmReset) {
                Button("Сбросить", role: .destructive) {
                    store.resetSettings()
                    theme = "system"
                    autoconnect = false
                    keepAlive = true
                }
                Button("Отмена", role: .cancel) {}
            } message: {
                Text("Тема, избранное, «Сразу в путь», «Всегда в пути» и выбранное направление вернутся к исходным. Код проездного останется.")
            }
            .alert("Выйти из аккаунта?", isPresented: $confirmLogout) {
                Button("Выйти", role: .destructive) { signOut() }
                Button("Отмена", role: .cancel) {}
            } message: {
                Text("Поездка остановится, а код проездного, избранное и конфигурация VPN в Настройках iOS удалятся с этого устройства. Код нужно будет вставить заново.")
            }
            .alert("Удалить аккаунт?", isPresented: $confirmDelete) {
                #if TITAN_PAYMENTS
                // Apple's rule: say billing goes on, and ask to cancel first.
                if appleSubscribed {
                    Button("Управление подпиской") { Task { await AppStorePurchases.shared.manageSubscriptions() } }
                }
                #endif
                Button("Удалить", role: .destructive) { deleteAccount() }
                Button("Отмена", role: .cancel) {}
            } message: {
                Text(deleteMessage)
            }
        }
    }

    /// Выйти: the VPN off for good, its profile and everything of the key off this device. Down
    /// already (maybe paused for a ping): iOS and the ping's end don't bring it back either.
    private func signOut() {
        vpn.removeProfile()
        store.logout()
    }

    /// Удалить аккаунт? asked once it is known whether an App Store subscription runs; without
    /// TITAN_PAYMENTS (nothing sold through the App Store) at once.
    private func askDelete() {
        #if TITAN_PAYMENTS
        Task {
            appleSubscribed = await AppStorePurchases.shared.hasSubscription()
            confirmDelete = true
        }
        #else
        confirmDelete = true
        #endif
    }

    /// What Удалить аккаунт does, as it will happen: through the bot if it has the endpoint,
    /// else a request for the support chat.
    private var deleteMessage: String {
        let how = Config.deletionEndpoint != nil
            ? "Аккаунт и проездной в боте удалятся сразу, код проездного и все данные — с этого устройства. Если сервер не ответит, мы скопируем запрос и откроем чат поддержки."
            : "Мы скопируем запрос на удаление и откроем чат поддержки — отправьте его там. Аккаунт и проездной в боте удалим в течение 7 дней, поддержка ответит, когда всё будет удалено. Код проездного и все данные с этого устройства удалятся сразу."
        #if TITAN_PAYMENTS
        let apple = appleSubscribed
            ? "У вас есть подписка App Store: Apple продолжит её списывать, пока вы её не отмените. Сначала отмените её в «Управлении подпиской»."
            : "Подписку App Store, если она есть, удаление не отменяет: её отменяют в настройках Apple ID."
        return how + "\n\n" + apple
        #else
        return how
        #endif
    }

    /// Удалить аккаунт: through the bot (Config.deletionEndpoint) when it can; else the bot's
    /// subscription is removed by support, so the request is copied for the support chat,
    /// which opens a moment after the note. Either way this device is signed out as by Выйти,
    /// its own App Store token goes too (with TITAN_PAYMENTS).
    private func deleteAccount() {
        let request = store.deletionRequest
        let support = store.supportURL
        deleting = true
        Task {
            let done = await store.deleteOnServer()
            deleting = false
            if !done { UIPasteboard.general.string = request }
            #if TITAN_PAYMENTS
            AppStorePurchases.shared.forgetKeylessToken()
            #endif
            signOut()
            guard !done else {
                store.showToast("Аккаунт удалён", .success)
                return
            }
            store.showToast("Запрос скопирован — отправьте его в поддержку", .success)
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            _ = await UIApplication.shared.open(support)
        }
    }

    /// Всегда в пути's line (Не отключать VPN before «Маршрут»). On iOS 16 the tunnel can't turn
    /// on demand off when the VPN is switched off in iOS Settings (PacketTunnelProvider.holdOnDemand
    /// needs iOS 17), so iOS turns it on again: where to turn it off, then.
    private var keepAliveNote: String {
        let note = "Сам перестроит маршрут при смене Wi‑Fi и мобильной сети, после сбоя и перезагрузки"
        if #available(iOS 17.0, *) { return note }
        return note + ". Останавливайтесь в приложении: если выключить VPN в Настройках iOS, он включится снова"
    }

    /// Dynamic Island: the Live Activity, the VPN on or off (on by default). Off ends it at once;
    /// on starts it.
    private var liveActivityRow: some View {
        VStack(alignment: .leading, spacing: 0) {
            Toggle(isOn: $liveActivity) {
                HStack(spacing: 14) {
                    IconTile(icon: "iphone", color: Palette.violet)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Dynamic Island").font(.scaled(16, weight: .medium))
                            .lineLimit(1).minimumScaleFactor(0.75)
                        Text("Направление, скорость и пинг на экране блокировки — и когда стоим")
                            .font(.scaled(12)).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .toggleStyle(TitanToggleStyle())
            .padding(.horizontal, 16).padding(.vertical, 12)
            .onChange(of: liveActivity) { on in
                if on {
                    LiveActivityController.sync(vpn.state, server: store.selected?.name, paused: store.vpnPaused)
                } else {
                    Task { await LiveActivityController.endAll() }
                }
            }
            if liveActivity && activitiesOff {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Live Activity для Titan VPS выключены в Настройках iOS")
                        .font(.scaled(12)).foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Открыть Настройки") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                    .font(.scaled(13, weight: .semibold))
                }
                // Under the text, as RowDivider.
                .padding(.leading, 62).padding(.trailing, 16).padding(.bottom, 12)
            }
        }
    }

    /// Without the flag: its circle is beside it.
    private var serverName: String {
        store.selected.map { RouteName.shown($0.name.splitFlag.1) } ?? "Автоматически"
    }

    /// Who this is: the subscription's state and devices, a tap opens Проездной.
    private func accountCard(_ sub: Subscription) -> some View {
        let now = Int64(Date().timeIntervalSince1970)
        let active = sub.info.expire <= 0 || sub.info.expire > now
        return Button {
            Haptics.selection()
            openSubscription()
        } label: {
            HStack(spacing: 14) {
                Image("Logo").resizable().scaledToFit().frame(width: 44, height: 44)
                    .padding(8)
                    .background(Palette.track, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text("Titan VPS").font(.scaled(19, weight: .bold))
                    HStack(spacing: 6) {
                        Circle().fill(active ? Palette.green : Color.red).frame(width: 8, height: 8)
                        Text(active ? (sub.info.expire > 0 ? "Проездной до " + Fmt.date(sub.info.expire) : "Проездной бессрочный") : "Проездной закончился")
                            .font(.scaled(14, weight: .medium))
                            .foregroundStyle(active ? Color.primary : Color.red)
                    }
                    if let acc = store.account, acc.devicesEnabled {
                        Text("Устройства: \(acc.devices.count)" + (acc.deviceLimit > 0 ? " из \(acc.deviceLimit)" : ""))
                            .font(.scaled(13)).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.scaled(14, weight: .semibold)).foregroundStyle(.tertiary)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Palette.primary.opacity(0.25), lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle(scale: 0.98))
        .padding(.top, 4)
    }

    @MainActor private func versionTapped() {
        let now = Date()
        taps = now.timeIntervalSince(lastTap) < 3 ? taps + 1 : 1
        lastTap = now
        guard taps >= 7 else { return }
        taps = 0
        code = ""
        askCode = true
    }

    /// Checked by hash only; a wrong code just says so for a moment.
    @MainActor private func enterCode() {
        let ok = TechMode.check(code)
        code = ""
        if ok {
            techMode = true
            showTech = true
        } else {
            wrongCode = true
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                wrongCode = false
            }
        }
    }
}

/// Системная / Светлая / Тёмная as three tiles; the picked one slides under the choice.
struct ThemePicker: View {
    @Binding var theme: String
    @Namespace private var selection

    private struct Option: Identifiable {
        let id: String
        let title: String
        let icon: String
    }

    private let options = [
        Option(id: "system", title: "Системная", icon: "circle.lefthalf.filled"),
        Option(id: "light", title: "Светлая", icon: "sun.max.fill"),
        Option(id: "dark", title: "Тёмная", icon: "moon.fill"),
    ]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(options) { o in
                let on = theme == o.id
                Button {
                    guard !on else { return }
                    Haptics.selection()
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { theme = o.id }
                } label: {
                    VStack(spacing: 6) {
                        Image(systemName: o.icon).font(.scaled(18, weight: .semibold))
                        Text(o.title).font(.scaled(13, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(on ? Color.white : Color.secondary)
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .background {
                        if on {
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(Palette.accentGradient)
                                .shadow(color: Palette.primary.opacity(0.35), radius: 4, y: 2)
                                .matchedGeometryEffect(id: "picked", in: selection)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressableStyle(scale: 0.95))
                .accessibilityLabel("Тема: " + o.title)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(6)
    }
}

/// О приложении: the logo, the version, where to write and what the app is built from.
struct AboutSheet: View {
    let version: String
    let support: URL
    #if TITAN_PAYMENTS
    /// The storefront: the bot itself (where it sells too) only in the United States one.
    @ObservedObject private var purchases = AppStorePurchases.shared
    #endif
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            content
                .padding(.horizontal, 24)
                .padding(.bottom, 20)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
        }
        .background(Palette.background.ignoresSafeArea())
        .presentationDetents([.height(440), .large])
        .presentationDragIndicator(.visible)
    }

    private var content: some View {
        VStack(spacing: 16) {
            Image("Logo").resizable().scaledToFit().frame(width: 96, height: 96)
                .shadow(color: Palette.primary.opacity(0.35), radius: 20, y: 8)
                .padding(.top, 28)
            VStack(spacing: 4) {
                Text("Titan VPS").font(.scaled(26, weight: .bold))
                Text("Версия \(version)").font(.scaled(15).monospacedDigit()).foregroundStyle(.secondary)
            }
            // The theme's words said plainly once: what the app does (App Review 2.3.1, 2.3.7).
            Text("«Маршрут» — это VPN-подключение через серверы Titan VPS: «Поехали» включает VPN, «Остановиться» выключает.")
                .font(.scaled(13)).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            chip("cpu", "Ядро Xray")
            VStack(spacing: 10) {
                Button { openURL(support) } label: {
                    Label("Поддержка", systemImage: "headphones")
                }
                .buttonStyle(PrimaryButtonStyle())
                // With the links kept to the US (AppStorePurchases.linksOut) the bot is reached as support.
                // Without TITAN_PAYMENTS too: Поддержка above only.
                #if TITAN_PAYMENTS
                if purchases.linksOut {
                    Button { openURL(Config.telegram) } label: {
                        Label("Telegram-бот", systemImage: "paperplane.fill")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                }
                #endif
                Button { openURL(Config.privacyPolicy) } label: {
                    Text("Политика конфиденциальности")
                        .font(.scaled(15, weight: .semibold))
                        .foregroundStyle(Palette.primary)
                        .frame(minHeight: 44)
                }
                .buttonStyle(PressableStyle())
            }
            openSource.padding(.top, 8)
        }
    }

    /// Открытый исходный код: the app and what it runs, each with its repository.
    private var openSource: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Открытый исходный код").font(.scaled(17, weight: .bold))
            Text("Код приложения и всего, на чём оно работает, открыт: что Titan VPS делает с трафиком, можно проверить.")
                .font(.scaled(13)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Card {
                ForEach(Array(OpenSource.all.enumerated()), id: \.offset) { i, part in
                    if i > 0 { RowDivider(inset: 16) }
                    row(part)
                }
            }
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(_ part: OpenSource) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(part.name).font(.scaled(15, weight: .semibold))
            Text(part.detail).font(.scaled(12)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            // Several links of one part side by side, wrapped by ViewThatFits on a narrow phone.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { links(part) }
                VStack(alignment: .leading, spacing: 6) { links(part) }
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func links(_ part: OpenSource) -> some View {
        ForEach(part.links, id: \.title) { link in
            Button { openURL(link.url) } label: {
                Label(link.title, systemImage: "arrow.up.right")
                    .labelStyle(TrailingIconLabel())
                    .font(.scaled(12, weight: .semibold))
                    .foregroundStyle(Palette.primary)
                    .lineLimit(1)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Palette.primary.opacity(0.12), in: Capsule())
            }
            .buttonStyle(PressableStyle())
            .accessibilityLabel("\(link.title), открыть на GitHub")
        }
    }

    private func chip(_ icon: String, _ text: String) -> some View {
        Label(text, systemImage: icon)
            .font(.scaled(13, weight: .semibold))
            .foregroundStyle(Palette.primary)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(Palette.primary.opacity(0.12), in: Capsule())
    }
}

/// A link's arrow after its title.
private struct TrailingIconLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.title
            configuration.icon.imageScale(.small)
        }
    }
}

/// What the app is built from (О приложении → Открытый исходный код; docs/HANDOFF.md).
struct OpenSource {
    struct Link {
        let title: String
        let url: URL
    }

    let name: String
    let detail: String
    let links: [Link]

    private static func github(_ path: String) -> URL { URL(string: "https://github.com/" + path)! }

    static let all = [
        // No other platforms named (App Review 2.3.10): the repository shows the rest.
        OpenSource(name: "Titan VPS", detail: "Исходный код приложения Titan VPS",
                   links: [Link(title: "UnionUnllimited/TitanApp", url: Config.repository)]),
        OpenSource(name: "Сетевое ядро", detail: "Xray-core v26.9.30 (через libXray), с нашими патчами для iOS",
                   links: [Link(title: "XTLS/Xray-core", url: github("XTLS/Xray-core")),
                           Link(title: "Патчи для iOS", url: Config.iosPatches)]),
        OpenSource(name: "libXray", detail: "Xray-core для мобильных приложений",
                   links: [Link(title: "XTLS/libXray", url: github("XTLS/libXray"))]),
        OpenSource(name: "sing-box", detail: "Клиенты протоколов TUIC, AnyTLS, ShadowTLS, SSH и Naive (Naive — на Cronet из Chromium)",
                   links: [Link(title: "SagerNet/sing-box", url: github("SagerNet/sing-box")),
                           Link(title: "SagerNet/cronet-go", url: github("SagerNet/cronet-go"))]),
        OpenSource(name: "mieru", detail: "Клиент протокола Mieru",
                   links: [Link(title: "enfein/mieru", url: github("enfein/mieru"))]),
        OpenSource(name: "flag-icons", detail: "Флаги стран в списке направлений",
                   links: [Link(title: "lipis/flag-icons", url: github("lipis/flag-icons"))]),
    ]
}
