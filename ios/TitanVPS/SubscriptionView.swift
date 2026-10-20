import SwiftUI

/// Подписка: expiry, bypass traffic and a "Личный кабинет" button with what it offers.
struct SubscriptionView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.openURL) private var openURL
    @State private var showCabinet = false
    @State private var showDevices = false

    var body: some View {
        ScrollView {
            if let sub = store.subscription {
                let info = sub.info
                let now = Int64(Date().timeIntervalSince1970)
                let active = info.expire <= 0 || info.expire > now
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Подписка").font(.system(size: 28, weight: .bold))
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
                                    .font(.system(size: 13, weight: .semibold))
                            }
                            .foregroundStyle(active ? Palette.green : .red)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background((active ? Palette.green : Color.red).opacity(0.14), in: Capsule())
                            Text(expiry(info.expire)).font(.system(size: 26, weight: .bold))
                            if info.expire > 0 {
                                Text("Осталось \(max((info.expire - now) / 86400, 0)) дн.").foregroundStyle(.secondary)
                            }
                            Text("Обновлено " + Fmt.ago(sub.fetchedAt)).font(.system(size: 12)).foregroundStyle(.tertiary)
                        }
                        .padding(16)
                    }
                    Card {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Трафик на обходах").foregroundStyle(.secondary).font(.system(size: 14))
                            if info.total > 0 {
                                Text("\(formatBytes(info.remaining)) из \(formatBytes(info.total))").font(.system(size: 26, weight: .bold))
                                GradientBar(value: Double(info.remaining) / Double(max(info.total, 1)),
                                            gradient: info.remaining * 10 < info.total ? LinearGradient(colors: [.orange, .red], startPoint: .leading, endPoint: .trailing) : Palette.accentGradient)
                            } else {
                                Text("Безлимит").font(.system(size: 26, weight: .bold))
                            }
                            Text("На обычных серверах — безлимит").font(.system(size: 13)).foregroundStyle(.secondary)
                            Text("Обходы — серверы для мобильного интернета, когда оператор пропускает только разрешённые сайты (белые списки) и обычные серверы не работают. Гигабайты списываются только пока вы подключены к обходу. На Wi-Fi обходы не нужны и недоступны. Закончились ГБ — докупите в личном кабинете.")
                                .font(.system(size: 12)).foregroundStyle(.secondary).padding(.top, 4)
                        }
                        .padding(16)
                    }
                    Button {
                        if info.webPageUrl != nil { showCabinet = true } else { openURL(Config.telegram) }
                    } label: {
                        Label("Личный кабинет", systemImage: "person.crop.circle.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    if let acc = store.account, acc.devicesEnabled {
                        Card {
                            Button { showDevices = true } label: {
                                SettingsRow(icon: "iphone.and.arrow.forward", color: Palette.primary, title: "Подключённые устройства", chevron: true) {
                                    Text("\(acc.devices.count)" + (acc.deviceLimit > 0 ? " из \(acc.deviceLimit)" : ""))
                                        .font(.system(size: 15, weight: .medium)).foregroundStyle(.secondary)
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                        }
                    }
                    Card {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("В личном кабинете").fontWeight(.semibold)
                            feature("arrow.triangle.2.circlepath", "Продление подписки")
                            feature("chart.pie", "Докупка ГБ для обходов")
                            feature("tag", "Коды оплаты")
                        }
                        .padding(16)
                    }
                    // What the in-app cabinet can't do: the bot or the full cabinet on the site.
                    Card {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Расширенные функции").fontWeight(.semibold)
                            Text("Изменение лимита устройств, отвязка устройств, рефералы и история платежей — в боте или в полном кабинете на сайте (вход по почте или через Telegram).")
                                .font(.system(size: 13)).foregroundStyle(.secondary)
                            HStack(spacing: 10) {
                                Button { openURL(Config.telegram) } label: {
                                    Label("Открыть бота", systemImage: "paperplane.fill").lineLimit(1)
                                }
                                .buttonStyle(SecondaryButtonStyle(height: 46))
                                if let site = info.webPageUrl.flatMap(URL.init(string:)).flatMap({ u -> URL? in
                                    guard let scheme = u.scheme, let host = u.host else { return nil }
                                    return URL(string: "\(scheme)://\(host)/auth")
                                }) {
                                    Button { openURL(site) } label: {
                                        Label("Открыть сайт", systemImage: "globe").lineLimit(1)
                                    }
                                    .buttonStyle(SecondaryButtonStyle(height: 46))
                                }
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
        .sheet(isPresented: $showDevices) {
            if let acc = store.account { DevicesView(account: acc) }
        }
        .sheet(isPresented: $showCabinet) {
            if let link = store.subscription?.info.webPageUrl {
                CabinetView(link: link, devices: store.account.map { ($0.devices.count, $0.deviceLimit) })
            }
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
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "d MMMM yyyy"
        return "до " + f.string(from: Date(timeIntervalSince1970: TimeInterval(expire)))
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
                                    .font(.system(size: 15))
                                Text([d.os + (d.osVersion.isEmpty ? "" : " \(d.osVersion)"), d.app.isEmpty ? "" : AccountAPI.appName(d.app)]
                                    .filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.system(size: 12)).foregroundStyle(.secondary)
                                if let seen = Self.lastSeen(d.lastSeen) {
                                    Text(seen).font(.system(size: 12)).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }
                } footer: {
                    Text("Отвязать лишнее устройство или изменить лимит можно в боте.")
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
    @State private var confirmLogout = false
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
                                Text(serverName).font(.system(size: 15)).foregroundStyle(.secondary).lineLimit(1)
                                    .frame(maxWidth: 170, alignment: .trailing)
                            }
                        }
                        .buttonStyle(RowButtonStyle())
                        RowDivider()
                        Toggle(isOn: $autoconnect) {
                            HStack(spacing: 14) {
                                IconTile(icon: "bolt.fill", color: Palette.green)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Автоподключение").font(.system(size: 16, weight: .medium))
                                    Text("VPN включится сам при запуске приложения, включении и после сна устройства")
                                        .font(.system(size: 12)).foregroundStyle(.secondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                        .toggleStyle(TitanToggleStyle())
                        .padding(.horizontal, 16).padding(.vertical, 12)
                        .onChange(of: autoconnect) { vpn.setAutoconnect($0) }
                    }

                    SectionTitle("Приложение")
                    Card {
                        Button { openURL(store.supportURL) } label: {
                            SettingsRow(icon: "headphones", color: Palette.primary, title: "Поддержка", subtitle: "Ответим в Telegram") {
                                Image(systemName: "arrow.up.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(.tertiary)
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
                            SettingsRow(icon: "info.circle.fill", color: Palette.violet, title: "О приложении", chevron: true)
                        }
                        .buttonStyle(RowButtonStyle())
                        RowDivider()
                        SettingsRow(icon: "number", color: .gray, title: "Версия") {
                            Text(wrongCode ? "Неверный код" : version)
                                .font(.system(size: 15).monospacedDigit())
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
                        RowDivider()
                        Button { confirmLogout = true } label: {
                            SettingsRow(icon: "rectangle.portrait.and.arrow.right", color: .red, title: "Выйти из аккаунта", titleColor: .red)
                        }
                        .buttonStyle(RowButtonStyle())
                    }
                    .padding(.top, 18)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle("Настройки")
            .navigationDestination(isPresented: $showTech) { TechModeView() }
            .sheet(isPresented: $about) { AboutSheet(version: version, support: store.supportURL) }
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
                }
                Button("Отмена", role: .cancel) {}
            } message: {
                Text("Тема, избранное, автоподключение и выбранный сервер вернутся к исходным. Ключ останется.")
            }
            .alert("Выйти из аккаунта?", isPresented: $confirmLogout) {
                Button("Выйти", role: .destructive) {
                    if vpn.isOn { vpn.disconnect() }
                    store.logout()
                }
                Button("Отмена", role: .cancel) {}
            } message: {
                Text("VPN отключится, ключ нужно будет вставить заново.")
            }
        }
    }

    private var serverName: String {
        guard let s = store.selected else { return "Автоматически" }
        let parts = s.name.splitFlag
        return [parts.0, parts.1].compactMap { $0 }.joined(separator: " ")
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
                    Text("Titan VPS").font(.system(size: 19, weight: .bold))
                    HStack(spacing: 6) {
                        Circle().fill(active ? Palette.green : Color.red).frame(width: 8, height: 8)
                        Text(active ? (sub.info.expire > 0 ? "Активна до " + Fmt.date(sub.info.expire) : "Подписка бессрочная") : "Подписка истекла")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(active ? Color.primary : Color.red)
                    }
                    if let acc = store.account, acc.devicesEnabled {
                        Text("Устройства: \(acc.devices.count)" + (acc.deviceLimit > 0 ? " из \(acc.deviceLimit)" : ""))
                            .font(.system(size: 13)).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(.tertiary)
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
                        Image(systemName: o.icon).font(.system(size: 18, weight: .semibold))
                        Text(o.title).font(.system(size: 13, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(on ? Color.white : Color.secondary)
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .background {
                        if on {
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(Palette.accentGradient)
                                .shadow(color: Palette.primary.opacity(0.35), radius: 8, y: 4)
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

/// О приложении: the logo, the version and where to write.
struct AboutSheet: View {
    let version: String
    let support: URL
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Image("Logo").resizable().scaledToFit().frame(width: 96, height: 96)
                .shadow(color: Palette.primary.opacity(0.35), radius: 20, y: 8)
                .padding(.top, 28)
            VStack(spacing: 4) {
                Text("Titan VPS").font(.system(size: 26, weight: .bold))
                Text("Версия \(version)").font(.system(size: 15).monospacedDigit()).foregroundStyle(.secondary)
            }
            chip("cpu", "Ядро Xray")
            Spacer(minLength: 0)
            VStack(spacing: 10) {
                Button { openURL(Config.telegram) } label: {
                    Label("Telegram-бот", systemImage: "paperplane.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
                Button { openURL(support) } label: {
                    Label("Поддержка", systemImage: "headphones")
                }
                .buttonStyle(SecondaryButtonStyle())
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private func chip(_ icon: String, _ text: String) -> some View {
        Label(text, systemImage: icon)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Palette.primary)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(Palette.primary.opacity(0.12), in: Capsule())
    }
}
