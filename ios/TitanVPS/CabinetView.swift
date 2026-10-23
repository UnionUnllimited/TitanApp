import SwiftUI

/// Personal cabinet in the app (same as Android's CabinetActivity, read only on iOS): the site's
/// limited cabinet, signed in by the subscription's link, shown natively. Renewing goes through
/// Тарифы (PaywallView, the App Store first): no tariffs, payments or codes here (App Review
/// 3.1.1).
struct CabinetView: View {
    let link: String
    /// Connected devices and the limit from the subscription's account info, if known.
    let devices: (count: Int, limit: Int)?
    /// Продлить подписку: Тарифы, once this sheet is down (one sheet at a time).
    var renew: () -> Void = {}

    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var api: CabinetAPI?
    @State private var data: CabinetAPI.Snapshot?
    @State private var error: String?
    @State private var loading = true
    @State private var copied = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let d = data {
                    ScrollView {
                        VStack(spacing: 14) {
                            if let error { banner(error, icon: "exclamationmark.triangle.fill", color: .red) }
                            subscription(d)
                            Button(action: renew) {
                                Label("Продлить подписку", systemImage: "crown.fill")
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            .padding(.top, 4)
                        }
                        .padding(16)
                        .frame(maxWidth: 640)
                        .frame(maxWidth: .infinity)
                    }
                    // In a Task of its own: SwiftUI cancels the pull's task when the screen
                    // changes under it (the refresh button starts spinning), and the request with it.
                    .refreshable { await Task { await load() }.value }
                } else if loading {
                    skeleton
                    Spacer(minLength: 0)
                } else {
                    failure
                    Spacer(minLength: 0)
                }
            }
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle("Личный кабинет")
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
                ToolbarItem(placement: .primaryAction) {
                    Button { Task { await load() } } label: {
                        SpinningIcon(name: "arrow.clockwise", spinning: loading)
                            .font(.scaled(13, weight: .bold))
                            .foregroundStyle(Palette.primary)
                            .frame(width: 32, height: 32)
                            .background(Palette.primary.opacity(0.14), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(loading)
                    .accessibilityLabel("Обновить")
                }
            }
        }
        .task { await load() }
    }

    private func load() async {
        loading = true
        error = nil
        defer { loading = false }
        do {
            if let api {
                data = try await api.load()
            } else {
                let (a, d) = try await CabinetAPI.open(link)
                api = a
                data = d
            }
            // Ваш ID names the account in a request to support (Удалить аккаунт).
            if let id = data?.clientId { store.rememberClientId(id) }
        } catch {
            self.error = (error as? CabinetAPI.Failure)?.message ?? "Не удалось загрузить кабинет. Проверьте интернет и попробуйте ещё раз"
        }
    }

    private func banner(_ text: String, icon: String, color: Color) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).foregroundStyle(color)
            Text(text).font(.scaled(14, weight: .medium)).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// While the cabinet loads the first time: the shapes of what is coming, with a shine.
    private var skeleton: some View {
        VStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Palette.surface).frame(height: 200)
            RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Palette.surface).frame(height: 110)
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Palette.surface).frame(height: 130)
                RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Palette.surface).frame(height: 130)
            }
        }
        .shimmer(tint: Palette.outline, duration: 1.3)
        .padding(16)
        .frame(maxWidth: 640)
        .accessibilityLabel("Загрузка")
    }

    private var failure: some View {
        VStack(spacing: 14) {
            Image(systemName: "wifi.exclamationmark")
                .font(.scaled(38, weight: .semibold))
                .foregroundStyle(Palette.primary)
                .frame(width: 88, height: 88)
                .background(Palette.primary.opacity(0.12), in: Circle())
            Text("Кабинет не загрузился").font(.scaled(19, weight: .bold))
            Text(error ?? "Ошибка").font(.scaled(14)).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button { Task { await load() } } label: { Label("Повторить", systemImage: "arrow.clockwise") }
                .buttonStyle(PrimaryButtonStyle(height: 50))
                .frame(maxWidth: 240)
                .padding(.top, 4)
        }
        .padding(32)
        .padding(.top, 24)
    }

    @ViewBuilder private func subscription(_ d: CabinetAPI.Snapshot) -> some View {
        hero(d)
        if let traffic = d.traffic {
            let used = min(max((d.trafficPercent ?? 0) / 100, 0), 1)
            Card {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        IconTile(icon: "chart.pie.fill", color: Palette.primary, size: 38)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Трафик").font(.scaled(13, weight: .semibold)).foregroundStyle(.secondary)
                            Text(traffic).font(.scaled(16, weight: .bold)).monospacedDigit()
                                .lineLimit(1).minimumScaleFactor(0.75)
                        }
                    }
                    GradientBar(value: used, gradient: trafficGradient(used))
                    if let n = d.trafficNote {
                        Text(n).font(.scaled(12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(18)
            }
        }
        let limit = d.deviceLimit ?? devices.flatMap { $0.limit > 0 ? $0.limit : nil }
        let showDevices = limit != nil || devices != nil
        if showDevices || d.clientId != nil {
            HStack(alignment: .top, spacing: 12) {
                if showDevices {
                    tile {
                        IconTile(icon: "iphone", color: Palette.violet, size: 38)
                        Text("Устройства").font(.scaled(13, weight: .semibold)).foregroundStyle(.secondary)
                        Text(devicesText(count: devices?.count, limit: limit))
                            .font(.scaled(28, weight: .heavy)).monospacedDigit()
                            .lineLimit(1).minimumScaleFactor(0.6)
                        Text("Лимит: " + (limit.map(devicesWord) ?? "без ограничения"))
                            .font(.scaled(13, weight: .medium)).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if let id = d.clientId {
                    Button {
                        UIPasteboard.general.string = id
                        Haptics.success()
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { copied = true }
                        Task {
                            try? await Task.sleep(nanoseconds: 1_600_000_000)
                            withAnimation { copied = false }
                        }
                    } label: {
                        tile {
                            IconTile(icon: copied ? "checkmark" : "person.text.rectangle.fill", color: copied ? Palette.green : .orange, size: 38)
                            Text("Ваш ID").font(.scaled(13, weight: .semibold)).foregroundStyle(.secondary)
                            Text(id).font(.scaled(20, weight: .heavy)).monospacedDigit()
                                .lineLimit(1).minimumScaleFactor(0.6)
                            Label(copied ? "Скопировано" : "Нажмите, чтобы скопировать",
                                  systemImage: copied ? "checkmark.circle.fill" : "doc.on.doc")
                                .font(.scaled(12, weight: .medium))
                                .foregroundStyle(copied ? Palette.green : Color.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .buttonStyle(PressableStyle(scale: 0.97))
                    .accessibilityLabel("Ваш ID \(id). Скопировать")
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Дни подписки on the brand gradient (red when it's over), a crown in the corner.
    private func hero(_ d: CabinetAPI.Snapshot) -> some View {
        let gradient = d.active ? Palette.accentGradient
            : LinearGradient(colors: [Color(red: 0.93, green: 0.27, blue: 0.33), Color(red: 0.62, green: 0.12, blue: 0.36)],
                             startPoint: .topLeading, endPoint: .bottomTrailing)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Circle().fill(Color.white).frame(width: 7, height: 7)
                Text(d.status.isEmpty ? (d.active ? "Активна" : "Неактивна") : d.status)
            }
            .font(.scaled(13, weight: .bold))
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(Color.white.opacity(0.2), in: Capsule())
            .padding(.bottom, 4)
            if !d.days.isEmpty {
                HStack(alignment: .lastTextBaseline, spacing: 6) {
                    Text(d.days).font(.scaled(64, weight: .black, design: .rounded))
                    Text(d.daysUnit).font(.scaled(20, weight: .semibold)).opacity(0.85)
                    if !d.hours.isEmpty { Text(d.hours).font(.scaled(22, weight: .bold)).opacity(0.85) }
                }
                .lineLimit(1).minimumScaleFactor(0.6)
                Text("ОСТАЛОСЬ").font(.scaled(11, weight: .semibold)).kerning(1.6).opacity(0.75)
            } else if !d.heroText.isEmpty {
                Text(d.heroText).font(.scaled(20, weight: .bold))
            }
            if !d.until.isEmpty {
                Label(d.until, systemImage: "calendar")
                    .font(.scaled(15, weight: .semibold))
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(Color.black.opacity(0.18), in: Capsule())
                    .padding(.top, 8)
            }
        }
        .foregroundStyle(.white)
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 24, style: .continuous).fill(gradient)
                Image(systemName: "crown.fill")
                    .font(.scaled(130))
                    .foregroundStyle(Color.white.opacity(0.12))
                    .rotationEffect(.degrees(-14))
                    .offset(x: 26, y: -6)
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(Color.white.opacity(0.15), lineWidth: 1))
        .shadow(color: (d.active ? Palette.primary : Color.red).opacity(0.35), radius: 18, y: 8)
    }

    /// A half-width card of the dashboard (devices, ID).
    private func tile<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) { content() }
            .padding(16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    /// Green while there's plenty, orange then red as it runs out.
    private func trafficGradient(_ used: Double) -> LinearGradient {
        if used < 0.7 { return Palette.greenGradient }
        let colors: [Color] = used < 0.9 ? [.yellow, .orange] : [.orange, .red]
        return LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing)
    }

    private func devicesText(count: Int?, limit: Int?) -> String {
        switch (count, limit) {
        case let (c?, l?): return "\(c) из \(l)"
        case let (c?, nil): return "\(c)"
        case let (nil, l?): return "до \(l)"
        default: return ""
        }
    }
}

private func devicesWord(_ n: Int) -> String {
    let word: String
    if (11...14).contains(n % 100) { word = "устройств" }
    else if n % 10 == 1 { word = "устройство" }
    else if (2...4).contains(n % 10) { word = "устройства" }
    else { word = "устройств" }
    return "\(n) \(word)"
}
