import SwiftUI

/// Подписка: expiry, bypass traffic, «Продлить подписку» (Тарифы) and a "Личный кабинет"
/// button with what it has.
struct SubscriptionView: View {
    @EnvironmentObject var store: AppStore
    @ObservedObject private var purchases = AppStorePurchases.shared
    @Environment(\.openURL) private var openURL
    @State private var showCabinet = false
    @State private var showDevices = false
    /// Продлить подписку in the cabinet: Тарифы once its sheet is down.
    @State private var renewAfterCabinet = false

    var body: some View {
        ScrollView {
            if let sub = store.subscription {
                let info = sub.info
                let now = Int64(Date().timeIntervalSince1970)
                let active = info.expire <= 0 || info.expire > now
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Подписка").font(.scaled(28, weight: .bold))
                        Spacer()
                        CircleIconButton(icon: "arrow.clockwise", spinning: store.busy, size: 42) {
                            Task { await store.refreshByHand() }
                        }
                        .disabled(store.busy)
                        .accessibilityLabel("Обновить подписку")
                    }
                    .padding(.top, 8)
                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 6) {
                                Circle().fill(active ? Palette.green : .red).frame(width: 8, height: 8)
                                Text(active ? "Подписка активна" : "Подписка истекла")
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
                            Text("Трафик на обходах").foregroundStyle(.secondary).font(.scaled(14))
                            if info.total > 0 {
                                Text("\(formatBytes(info.remaining)) из \(formatBytes(info.total))").font(.scaled(26, weight: .bold))
                                GradientBar(value: Double(info.remaining) / Double(max(info.total, 1)),
                                            gradient: info.remaining * 10 < info.total ? LinearGradient(colors: [.orange, .red], startPoint: .leading, endPoint: .trailing) : Palette.accentGradient)
                            } else {
                                Text("Безлимит").font(.scaled(26, weight: .bold))
                            }
                            Text("На обычных серверах — безлимит").font(.scaled(13)).foregroundStyle(.secondary)
                            Text("Обходы — серверы для мобильного интернета, когда оператор пропускает только разрешённые сайты (белые списки) и обычные серверы не работают. Гигабайты списываются только пока вы подключены к обходу. На Wi-Fi обходы не нужны и недоступны." + (info.total > 0 ? " Закончились ГБ — нажмите «Добавить ГБ»." : ""))
                                .font(.scaled(12)).foregroundStyle(.secondary).padding(.top, 4)
                            if info.total > 0 {
                                // Тарифы (the App Store first); orange once less than 10 % is left.
                                let low = info.remaining * 10 < info.total
                                Button { store.paywall = .traffic } label: {
                                    Label("Добавить ГБ", systemImage: "plus.circle.fill").lineLimit(1)
                                }
                                .buttonStyle(SecondaryButtonStyle(height: 44, tint: low ? Color.orange : Palette.primary))
                                .padding(.top, 4)
                            }
                        }
                        .padding(16)
                    }
                    Button { store.paywall = renewReason } label: {
                        Label("Продлить подписку", systemImage: "crown.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    // Without the site's link, the bot instead: the United States storefront only,
                    // as it sells subscriptions too (App Review 3.1.1(a)).
                    let cabinet = info.webPageUrl != nil || purchases.linksOut
                    if cabinet {
                        Button {
                            if info.webPageUrl != nil { showCabinet = true } else { openURL(Config.telegram) }
                        } label: {
                            Label("Личный кабинет", systemImage: "person.crop.circle.fill")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                    }
                    if let acc = store.account, acc.devicesEnabled {
                        Card {
                            Button { showDevices = true } label: {
                                SettingsRow(icon: "iphone.and.arrow.forward", color: Palette.primary, title: "Подключённые устройства", chevron: true) {
                                    Text("\(acc.devices.count)" + (acc.deviceLimit > 0 ? " из \(acc.deviceLimit)" : ""))
                                        .font(.scaled(15, weight: .medium)).foregroundStyle(.secondary)
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                        }
                    }
                    // What the in-app cabinet has; the site's tariffs and codes as
                    // AppStorePurchases.otherMethods allows.
                    if cabinet {
                        Card {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("В личном кабинете").fontWeight(.semibold)
                                feature("calendar", "Сколько дней осталось")
                                feature("chart.pie", "Трафик на обходах")
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
                }
                .padding(16)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
        }
        .background(Palette.background.ignoresSafeArea())
        .refreshable { await store.refreshFromPull() }
        .task { await store.loadAccount() }
        // Тарифы from a reminder (RootView): this tab's sheets go down first.
        .onChange(of: store.closeSheets) { _ in
            showCabinet = false
            showDevices = false
        }
        .sheet(isPresented: $showDevices) {
            if let acc = store.account { DevicesView(account: acc) }
        }
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

    private func expiry(_ expire: Int64) -> String {
        guard expire > 0 else { return "Бессрочно" }
        return "до " + Fmt.date(expire)
    }
}

/// The devices that used the key, and the limit (from the bot's /api/sub/…/info).
struct DevicesView: View {
    let account: AccountAPI.Info
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Text("Подключено")
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
                    Text("Отвязать лишнее устройство можно в боте.")
                }
                Section {
                    Button { openURL(Config.telegram) } label: { Label("Открыть бота", systemImage: "paperplane") }
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
    /// Удалить аккаунт found an App Store subscription: Apple keeps billing it until cancelled.
    @State private var appleSubscribed = false
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

    private var version: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "") (\(info?["CFBundleVersion"] as? String ?? ""))"
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if let sub = store.subscription { accountCard(sub) }

                    SectionTitle("Оформление")
                    Card { ThemePicker(theme: $theme) }

                    SectionTitle("Подключение")
                    Card {
                        Button {
                            Haptics.selection()
                            openHome()
                        } label: {
                            SettingsRow(icon: "server.rack", color: Palette.primary, title: "Сервер", chevron: true) {
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
                                IconTile(icon: "bolt.fill", color: Palette.green)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Автоподключение").font(.scaled(16, weight: .medium))
                                        .lineLimit(1).minimumScaleFactor(0.75)
                                    Text("VPN включится сам при открытии приложения")
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
                                IconTile(icon: "checkmark.shield.fill", color: Palette.green)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Не отключать VPN").font(.scaled(16, weight: .medium))
                                        .lineLimit(1).minimumScaleFactor(0.75)
                                    Text("Сам переподключится при смене Wi‑Fi и мобильной сети, после сбоя и перезагрузки")
                                        .font(.scaled(12)).foregroundStyle(.secondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                        .toggleStyle(TitanToggleStyle())
                        .padding(.horizontal, 16).padding(.vertical, 12)
                        .onChange(of: keepAlive) { vpn.setKeepAlive($0) }
                        RowDivider()
                        Toggle(isOn: $reminders) {
                            HStack(spacing: 14) {
                                IconTile(icon: "bell.badge.fill", color: .orange)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Напоминания").font(.scaled(16, weight: .medium))
                                        .lineLimit(1).minimumScaleFactor(0.75)
                                    Text("За 3 дня и за день до конца подписки и когда на обходах кончается трафик")
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
                            SettingsRow(icon: "arrow.clockwise", color: Palette.green, title: "Обновить подписку",
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
            }
            .background(Palette.background.ignoresSafeArea())
            // Back from iOS Settings: maybe allowed there now.
            .onAppear { activitiesOff = !LiveActivityController.allowedInSettings }
            .onChange(of: scenePhase) { if $0 == .active { activitiesOff = !LiveActivityController.allowedInSettings } }
            .navigationTitle("Настройки")
            // The system tab bar stays hidden here and on a pushed screen too (RootView's TitanTabBar).
            .toolbar(.hidden, for: .tabBar)
            .navigationDestination(isPresented: $showTech) { TechModeView().toolbar(.hidden, for: .tabBar) }
            .sheet(isPresented: $about) { AboutSheet(version: version, support: store.supportURL) }
            // Тарифы from a reminder (RootView): О приложении goes down first.
            .onChange(of: store.closeSheets) { _ in about = false }
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
                Text("Тема, избранное, автоподключение, «Не отключать VPN» и выбранный сервер вернутся к исходным. Ключ останется.")
            }
            .alert("Выйти из аккаунта?", isPresented: $confirmLogout) {
                Button("Выйти", role: .destructive) { signOut() }
                Button("Отмена", role: .cancel) {}
            } message: {
                Text("VPN отключится, ключ, избранное и VPN-профиль удалятся с этого устройства. Ключ нужно будет вставить заново.")
            }
            .alert("Удалить аккаунт?", isPresented: $confirmDelete) {
                // Apple's rule: say billing goes on, and ask to cancel first.
                if appleSubscribed {
                    Button("Управление подпиской") { Task { await AppStorePurchases.shared.manageSubscriptions() } }
                }
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

    /// Удалить аккаунт? asked once it is known whether an App Store subscription runs.
    private func askDelete() {
        Task {
            appleSubscribed = await AppStorePurchases.shared.hasSubscription()
            confirmDelete = true
        }
    }

    /// What Удалить аккаунт does, as it will happen: through the bot if it has the endpoint,
    /// else a request for the support chat.
    private var deleteMessage: String {
        let how = Config.deletionEndpoint != nil
            ? "Аккаунт и подписка в боте удалятся сразу, ключ и все данные — с этого устройства. Если сервер не ответит, мы скопируем запрос и откроем чат поддержки."
            : "Мы скопируем запрос на удаление и откроем чат поддержки — отправьте его там. Аккаунт и подписку в боте удалим в течение 7 дней, поддержка ответит, когда всё будет удалено. Ключ и все данные с этого устройства удалятся сразу."
        let apple = appleSubscribed
            ? "У вас есть подписка App Store: Apple продолжит её списывать, пока вы её не отмените. Сначала отмените её в «Управлении подпиской»."
            : "Подписку App Store, если она есть, удаление не отменяет: её отменяют в настройках Apple ID."
        return how + "\n\n" + apple
    }

    /// Удалить аккаунт: through the bot (Config.deletionEndpoint) when it can; else the bot's
    /// subscription is removed by support, so the request is copied for the support chat,
    /// which opens a moment after the note. Either way this device is signed out as by Выйти,
    /// its own App Store token goes too.
    private func deleteAccount() {
        let request = store.deletionRequest
        let support = store.supportURL
        deleting = true
        Task {
            let done = await store.deleteOnServer()
            deleting = false
            if !done { UIPasteboard.general.string = request }
            AppStorePurchases.shared.forgetKeylessToken()
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

    /// Dynamic Island: the Live Activity while connected (on by default). Off ends it at once;
    /// on while connected starts it.
    private var liveActivityRow: some View {
        VStack(alignment: .leading, spacing: 0) {
            Toggle(isOn: $liveActivity) {
                HStack(spacing: 14) {
                    IconTile(icon: "iphone", color: Palette.violet)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Dynamic Island").font(.scaled(16, weight: .medium))
                            .lineLimit(1).minimumScaleFactor(0.75)
                        Text("Сервер и его смена на экране блокировки")
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
        store.selected?.name.splitFlag.1 ?? "Автоматически"
    }

    /// Who this is: the subscription's state and devices, a tap opens Подписка.
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
                        Text(active ? (sub.info.expire > 0 ? "Активна до " + Fmt.date(sub.info.expire) : "Подписка бессрочная") : "Подписка истекла")
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
    /// The storefront: the bot itself (where it sells too) only in the United States one.
    @ObservedObject private var purchases = AppStorePurchases.shared
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
            chip("cpu", "Ядро Xray")
            VStack(spacing: 10) {
                Button { openURL(support) } label: {
                    Label("Поддержка", systemImage: "headphones")
                }
                .buttonStyle(PrimaryButtonStyle())
                // Elsewhere the bot is reached as support (App Review 3.1.1(a)).
                if purchases.linksOut {
                    Button { openURL(Config.telegram) } label: {
                        Label("Telegram-бот", systemImage: "paperplane.fill")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                }
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
        OpenSource(name: "Ядро VPN", detail: "Xray-core v26.9.30 (через libXray), с нашими патчами для iOS",
                   links: [Link(title: "XTLS/Xray-core", url: github("XTLS/Xray-core")),
                           Link(title: "Патчи для iOS", url: Config.iosPatches)]),
        OpenSource(name: "libXray", detail: "Xray-core для мобильных приложений",
                   links: [Link(title: "XTLS/libXray", url: github("XTLS/libXray"))]),
        OpenSource(name: "sing-box", detail: "Клиенты протоколов TUIC, AnyTLS, ShadowTLS, SSH и Naive (Naive — на Cronet из Chromium)",
                   links: [Link(title: "SagerNet/sing-box", url: github("SagerNet/sing-box")),
                           Link(title: "SagerNet/cronet-go", url: github("SagerNet/cronet-go"))]),
        OpenSource(name: "mieru", detail: "Клиент протокола Mieru",
                   links: [Link(title: "enfein/mieru", url: github("enfein/mieru"))]),
        OpenSource(name: "flag-icons", detail: "Флаги стран в списке серверов",
                   links: [Link(title: "lipis/flag-icons", url: github("lipis/flag-icons"))]),
    ]
}
