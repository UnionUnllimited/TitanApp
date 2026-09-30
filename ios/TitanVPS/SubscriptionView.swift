import SwiftUI

/// Подписка: expiry, bypass traffic and a "Личный кабинет" button with what it offers.
struct SubscriptionView: View {
    @EnvironmentObject var store: AppStore
    @Environment(\.openURL) private var openURL

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
                    Button {
                        Task {
                            await store.refresh()
                            let page = store.subscription?.info.webPageUrl.flatMap(URL.init(string:))
                            openURL(page ?? Config.telegram)
                        }
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
                            feature("laptopcomputer.and.iphone", "Управление устройствами")
                            feature("tag", "Промокоды и история платежей")
                            feature("headphones", "Поддержка")
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
