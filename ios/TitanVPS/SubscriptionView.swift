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
                    Text("Подписка").font(.system(size: 28, weight: .bold)).padding(.top, 8)
                    Card {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 8) {
                                Circle().fill(active ? Palette.green : .red).frame(width: 10, height: 10)
                                Text(active ? "Подписка активна" : "Подписка истекла")
                                    .foregroundStyle(active ? Palette.green : .red).font(.system(size: 14))
                            }
                            Text(expiry(info.expire)).font(.system(size: 26, weight: .bold))
                            if info.expire > 0 {
                                Text("Осталось \(max((info.expire - now) / 86400, 0)) дн.").foregroundStyle(.secondary)
                            }
                        }
                        .padding(16)
                    }
                    Card {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Трафик на обходах").foregroundStyle(.secondary).font(.system(size: 14))
                            if info.total > 0 {
                                Text("\(formatBytes(info.remaining)) из \(formatBytes(info.total))").font(.system(size: 26, weight: .bold))
                                ProgressView(value: Double(info.remaining), total: Double(info.total))
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
                        Label("Личный кабинет", systemImage: "person.crop.circle")
                            .font(.system(size: 17, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 50)
                    }
                    .buttonStyle(.borderedProminent)
                    if let acc = store.account, acc.devicesEnabled {
                        Button { showDevices = true } label: {
                            HStack {
                                Label("Подключённые устройства", systemImage: "iphone.and.arrow.forward")
                                Spacer()
                                Text("\(acc.devices.count)" + (acc.deviceLimit > 0 ? " из \(acc.deviceLimit)" : ""))
                                    .foregroundStyle(.secondary)
                                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(.secondary)
                            }
                            .font(.system(size: 16, weight: .medium))
                            .frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .buttonStyle(.bordered)
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
                                Button { openURL(Config.telegram) } label: { Text("Открыть бота").lineLimit(1).frame(maxWidth: .infinity) }
                                    .buttonStyle(.bordered)
                                if let site = info.webPageUrl.flatMap(URL.init(string:)).flatMap({ u -> URL? in
                                    guard let scheme = u.scheme, let host = u.host else { return nil }
                                    return URL(string: "\(scheme)://\(host)/auth")
                                }) {
                                    Button { openURL(site) } label: { Text("Открыть сайт").lineLimit(1).frame(maxWidth: .infinity) }
                                        .buttonStyle(.bordered)
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
    @AppStorage("theme") private var theme = "system"
    @AppStorage("autoconnect") private var autoconnect = false
    @State private var confirmLogout = false
    @State private var confirmReset = false
    @State private var about = false

    private var version: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "") (\(info?["CFBundleVersion"] as? String ?? ""))"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Оформление") {
                    Picker("Тема", selection: $theme) {
                        Text("Системная").tag("system")
                        Text("Светлая").tag("light")
                        Text("Тёмная").tag("dark")
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    HStack {
                        Text("Выбор сервера")
                        Spacer()
                        Text(store.selected.map { $0.name.splitFlag.1 } ?? "Автоматически")
                            .foregroundStyle(.secondary).lineLimit(1)
                    }
                    Toggle(isOn: $autoconnect) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Автоподключение")
                            Text("VPN включится сам при запуске приложения, включении и после сна устройства")
                                .font(.system(size: 12)).foregroundStyle(.secondary)
                        }
                    }
                    .onChange(of: autoconnect) { vpn.setAutoconnect($0) }
                } header: {
                    Text("Подключение")
                }
                Section("Приложение") {
                    Link(destination: store.supportURL) { Label("Поддержка", systemImage: "headphones") }
                    Button { Task { await store.refresh() } } label: { Label("Обновить подписку", systemImage: "arrow.clockwise") }
                    Button { about = true } label: { Label("О приложении", systemImage: "info.circle") }
                }
                Section {
                    Button("Сброс настроек") { confirmReset = true }
                    Button("Выйти из аккаунта", role: .destructive) { confirmLogout = true }
                }
            }
            .navigationTitle("Настройки")
            .alert("Titan VPS", isPresented: $about) {
                Button("Закрыть", role: .cancel) {}
            } message: {
                Text("Версия \(version) · ядро Xray")
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
}
