import SwiftUI

/// Подписка: expiry, bypass traffic and a "Личный кабинет" button with what it offers.
struct SubscriptionView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.openURL) private var openURL
    @State private var showCabinet = false

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
                        }
                        .padding(16)
                    }
                    if let acc = store.account, acc.devicesEnabled {
                        Card {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Устройства").foregroundStyle(.secondary).font(.system(size: 14))
                                Text("\(acc.devices.count)" + (acc.deviceLimit > 0 ? " из \(acc.deviceLimit)" : ""))
                                    .font(.system(size: 26, weight: .bold))
                                ForEach(acc.devices) { d in
                                    HStack(spacing: 10) {
                                        Image(systemName: d.os.lowercased().contains("ios") ? "iphone" : d.os.lowercased().contains("android") ? "smartphone" : "desktopcomputer")
                                            .foregroundStyle(Palette.primary).frame(width: 22)
                                        VStack(alignment: .leading, spacing: 1) {
                                            Text([d.model, d.os + (d.osVersion.isEmpty ? "" : " \(d.osVersion)")].filter { !$0.isEmpty }.joined(separator: " · "))
                                                .font(.system(size: 14))
                                            if !d.app.isEmpty { Text(d.app).font(.system(size: 12)).foregroundStyle(.secondary) }
                                        }
                                    }
                                }
                                Text("Чтобы отвязать лишнее устройство, напишите в поддержку.").font(.system(size: 12)).foregroundStyle(.secondary)
                            }
                            .padding(16)
                        }
                    }
                    Button {
                        if info.webPageUrl != nil { showCabinet = true } else { openURL(Config.telegram) }
                    } label: {
                        Label("Личный кабинет", systemImage: "person.crop.circle")
                            .font(.system(size: 17, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 50)
                    }
                    .buttonStyle(.borderedProminent)
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
                                Button { openURL(Config.telegram) } label: { Text("Открыть бота").frame(maxWidth: .infinity) }
                                    .buttonStyle(.bordered)
                                if let site = info.webPageUrl.flatMap(URL.init(string:)).flatMap({ u -> URL? in
                                    guard let scheme = u.scheme, let host = u.host else { return nil }
                                    return URL(string: "\(scheme)://\(host)/auth")
                                }) {
                                    Button { openURL(site) } label: { Text("Кабинет на сайте").frame(maxWidth: .infinity) }
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

struct ProfileView: View {
    @EnvironmentObject var store: AppStore
    @AppStorage("theme") private var theme = "system"
    @State private var confirmLogout = false

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
                Section("Приложение") {
                    Link(destination: Config.telegram) { Label("Поддержка", systemImage: "headphones") }
                    Button { Task { await store.refresh() } } label: { Label("Обновить подписку", systemImage: "arrow.clockwise") }
                    HStack {
                        Text("Версия")
                        Spacer()
                        Text((Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") + " (тест iOS)")
                            .foregroundStyle(.secondary)
                    }
                }
                Section {
                    Button("Выйти", role: .destructive) { confirmLogout = true }
                }
            }
            .navigationTitle("Настройки")
            .confirmationDialog("Выйти из аккаунта?", isPresented: $confirmLogout, titleVisibility: .visible) {
                Button("Выйти", role: .destructive) { store.logout() }
            }
        }
    }
}
