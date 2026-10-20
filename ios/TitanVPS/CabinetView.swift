import SafariServices
import SwiftUI

/// Personal cabinet in the app (same as Android's CabinetActivity): the site's limited cabinet,
/// signed in by the subscription's link, shown natively. Only the payment page itself opens
/// (in an in-app Safari sheet; bank apps for SBP open from there).
struct CabinetView: View {
    let link: String
    /// Connected devices and the limit from the subscription's account info, if known.
    let devices: (count: Int, limit: Int)?

    @Environment(\.dismiss) private var dismiss
    @State private var api: CabinetAPI?
    @State private var data: CabinetAPI.Snapshot?
    @State private var error: String?
    @State private var loading = true
    @State private var tab = 0
    @State private var note: String?
    @State private var payURL: URL?
    @State private var copied = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                SegmentedTabs(items: [
                    SegmentedTabs.Item(id: 0, title: "Подписка", icon: "crown.fill"),
                    SegmentedTabs.Item(id: 1, title: "Оплатить", icon: "creditcard.fill"),
                ], selection: $tab)
                .padding(.horizontal, 16)
                .padding(.top, 4)
                if let d = data {
                    ScrollView {
                        VStack(spacing: 14) {
                            if let note { banner(note, icon: "info.circle.fill", color: Palette.primary) }
                            if let error { banner(error, icon: "exclamationmark.triangle.fill", color: .red) }
                            if tab == 0 {
                                subscription(d).transition(.opacity)
                            } else {
                                PayPane(data: d, onPay: pay, onCode: applyCode).transition(.opacity)
                            }
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
                            .font(.system(size: 13, weight: .bold))
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
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Palette.primary)
                            .frame(width: 32, height: 32)
                            .background(Palette.primary.opacity(0.14), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(loading)
                    .accessibilityLabel("Обновить")
                }
            }
            .sheet(item: $payURL, onDismiss: { Task { await load() } }) { url in SafariView(url: url).ignoresSafeArea() }
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
        } catch {
            self.error = (error as? CabinetAPI.Failure)?.message ?? "Не удалось загрузить кабинет. Проверьте интернет и попробуйте ещё раз"
        }
    }

    private func pay(_ tariff: CabinetAPI.Tariff, _ method: String) async {
        guard let api else { return }
        do { payURL = try await api.pay(tariff, method: method) } catch { note = message(error) }
    }

    private func applyCode(_ code: String) async -> Bool {
        guard let api else { return false }
        do {
            note = try await api.applyCode(code)
            await load()
            return true
        } catch {
            note = message(error)
            return false
        }
    }

    private func message(_ error: Error) -> String { (error as? CabinetAPI.Failure)?.message ?? "Ошибка соединения" }

    private func banner(_ text: String, icon: String, color: Color) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).foregroundStyle(color)
            Text(text).font(.system(size: 14, weight: .medium)).fixedSize(horizontal: false, vertical: true)
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
                .font(.system(size: 38, weight: .semibold))
                .foregroundStyle(Palette.primary)
                .frame(width: 88, height: 88)
                .background(Palette.primary.opacity(0.12), in: Circle())
            Text("Кабинет не загрузился").font(.system(size: 19, weight: .bold))
            Text(error ?? "Ошибка").font(.system(size: 14)).multilineTextAlignment(.center).foregroundStyle(.secondary)
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
                            Text("Трафик").font(.system(size: 13, weight: .semibold)).foregroundStyle(.secondary)
                            Text(traffic).font(.system(size: 16, weight: .bold)).monospacedDigit()
                                .lineLimit(1).minimumScaleFactor(0.75)
                        }
                    }
                    GradientBar(value: used, gradient: trafficGradient(used))
                    if let n = d.trafficNote {
                        Text(n).font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
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
                        Text("Устройства").font(.system(size: 13, weight: .semibold)).foregroundStyle(.secondary)
                        Text(devicesText(count: devices?.count, limit: limit))
                            .font(.system(size: 28, weight: .heavy)).monospacedDigit()
                            .lineLimit(1).minimumScaleFactor(0.6)
                        Text("Лимит: " + (limit.map(devicesWord) ?? "без ограничения"))
                            .font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
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
                            Text("Ваш ID").font(.system(size: 13, weight: .semibold)).foregroundStyle(.secondary)
                            Text(id).font(.system(size: 20, weight: .heavy)).monospacedDigit()
                                .lineLimit(1).minimumScaleFactor(0.6)
                            Label(copied ? "Скопировано" : "Нажмите, чтобы скопировать",
                                  systemImage: copied ? "checkmark.circle.fill" : "doc.on.doc")
                                .font(.system(size: 12, weight: .medium))
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
            .font(.system(size: 13, weight: .bold))
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(Color.white.opacity(0.2), in: Capsule())
            .padding(.bottom, 4)
            if !d.days.isEmpty {
                HStack(alignment: .lastTextBaseline, spacing: 6) {
                    Text(d.days).font(.system(size: 64, weight: .black, design: .rounded))
                    Text(d.daysUnit).font(.system(size: 20, weight: .semibold)).opacity(0.85)
                    if !d.hours.isEmpty { Text(d.hours).font(.system(size: 22, weight: .bold)).opacity(0.85) }
                }
                .lineLimit(1).minimumScaleFactor(0.6)
                Text("ОСТАЛОСЬ").font(.system(size: 11, weight: .semibold)).kerning(1.6).opacity(0.75)
            } else if !d.heroText.isEmpty {
                Text(d.heroText).font(.system(size: 20, weight: .bold))
            }
            if !d.until.isEmpty {
                Label(d.until, systemImage: "calendar")
                    .font(.system(size: 15, weight: .semibold))
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
                    .font(.system(size: 130))
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

private struct PayPane: View {
    let data: CabinetAPI.Snapshot
    let onPay: (CabinetAPI.Tariff, String) async -> Void
    let onCode: (String) async -> Bool

    @State private var busy = false
    @State private var limit: Int?
    @State private var picked: [String: String] = [:]
    @State private var codeOpen = false
    @State private var code = ""

    var body: some View {
        VStack(spacing: 14) {
            ForEach(["sub", "traffic"], id: \.self) { kind in
                let all = data.tariffs.filter { $0.kind == kind }
                if !all.isEmpty { section(kind, all) }
            }
            if data.tariffs.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "creditcard.trianglebadge.exclamationmark")
                        .font(.system(size: 34, weight: .semibold)).foregroundStyle(.secondary)
                    Text("Сейчас оплата недоступна").foregroundStyle(.secondary)
                }
                .padding(.vertical, 32)
            }
            // A payment code is rare: a quiet button at the bottom, the field in a dialog.
            if data.promo {
                Button { codeOpen = true } label: {
                    Label("Есть код оплаты?", systemImage: "ticket.fill")
                }
                .buttonStyle(SecondaryButtonStyle(height: 46, tint: .secondary))
            }
        }
        .onAppear { if limit == nil { limit = data.limits.first } }
        .alert("Код оплаты", isPresented: $codeOpen) {
            TextField("Введите код", text: $code).textInputAutocapitalization(.characters)
            Button("Применить") {
                let c = code.trimmingCharacters(in: .whitespaces)
                Task { if await onCode(c) { code = "" } }
            }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Продлевает подписку на указанное в коде число дней. Один код — один раз на аккаунт.")
        }
    }

    @ViewBuilder private func section(_ kind: String, _ all: [CabinetAPI.Tariff]) -> some View {
        let visible = kind == "sub" && !data.limits.isEmpty ? all.filter { $0.limit == limit } : {
            let shown = all.filter(\.shown)
            return shown.isEmpty ? all : shown
        }()
        let selected = visible.first { $0.id == picked[kind] } ?? (visible.count == 1 ? visible.first : nil)
        Card {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    IconTile(icon: kind == "sub" ? "crown.fill" : "chart.pie.fill",
                             color: kind == "sub" ? Palette.primary : Palette.violet, size: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(kind == "sub" ? "Продлить подписку" : "Докупить трафик").font(.system(size: 17, weight: .bold))
                        Text(kind == "sub" ? "Дни добавятся к текущей подписке" : "Гигабайты для обходов")
                            .font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                }
                if kind == "sub" && !data.limits.isEmpty { limits(kind) }
                ForEach(visible) { t in tariffRow(t, kind: kind, on: selected?.id == t.id) }
                if let t = selected {
                    let methods = (data.methods[kind] ?? []).filter { t.methods.contains($0.id) }
                    let list = methods.isEmpty ? t.methods.map { CabinetAPI.Method(id: $0, title: "") } : methods
                    if list.isEmpty { Text("Оплата для этого тарифа недоступна").font(.system(size: 13)).foregroundStyle(.red) }
                    ForEach(list, id: \.id) { m in
                        Button {
                            Haptics.tap(.medium)
                            busy = true
                            Task { await onPay(t, m.id); busy = false }
                        } label: {
                            if busy { ProgressView().tint(.white) } else {
                                Label("Оплатить \(price(t.price))" + (m.title.isEmpty ? "" : " · \(m.title)"), systemImage: "creditcard.fill")
                                    .lineLimit(1).minimumScaleFactor(0.8)
                            }
                        }
                        .buttonStyle(PrimaryButtonStyle(height: 52))
                        .disabled(busy)
                    }
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                } else if !visible.isEmpty {
                    Text("Выберите тариф, чтобы оплатить").font(.system(size: 12)).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(18)
        }
    }

    /// Лимит устройств: how many at once; fixed after the first payment.
    private func limits(_ kind: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Лимит устройств", systemImage: "iphone")
                .font(.system(size: 13, weight: .semibold)).foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(data.limits, id: \.self) { l in
                        let on = limit == l
                        Button {
                            guard !on else { return }
                            Haptics.selection()
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                                limit = l
                                picked[kind] = nil
                            }
                        } label: {
                            Text("\(l)")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(on ? Color.white : Color.primary)
                                .frame(width: 46, height: 42)
                                .background(on ? AnyShapeStyle(Palette.accentGradient) : AnyShapeStyle(Palette.track),
                                            in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .shadow(color: Palette.primary.opacity(on ? 0.35 : 0), radius: 6, y: 3)
                        }
                        .buttonStyle(PressableStyle(scale: 0.9))
                        .accessibilityLabel("\(l) устр.")
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                .padding(.vertical, 4)
            }
            Text("Сколько устройств одновременно. Фиксируется после первой оплаты.")
                .font(.system(size: 12)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func tariffRow(_ t: CabinetAPI.Tariff, kind: String, on: Bool) -> some View {
        let meta = [t.limitLabel, t.trafficLabel].filter { !$0.isEmpty }.joined(separator: " · ")
        return Button {
            Haptics.selection()
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { picked[kind] = t.id }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().stroke(on ? Palette.primary : Palette.outline, lineWidth: 2).frame(width: 22, height: 22)
                    if on { Circle().fill(Palette.primary).frame(width: 12, height: 12).transition(.scale) }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(t.name).font(.system(size: 16, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                    if !meta.isEmpty { Text(meta).font(.system(size: 12)).foregroundStyle(.secondary) }
                }
                Spacer(minLength: 8)
                Text(price(t.price))
                    .font(.system(size: 18, weight: .heavy)).monospacedDigit()
                    .foregroundStyle(on ? Palette.primary : Color.primary)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(minHeight: 62)
            .background(on ? Palette.primary.opacity(0.1) : Palette.track.opacity(0.55),
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(on ? Palette.primary : Color.clear, lineWidth: 1.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle(scale: 0.98))
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

private func price(_ p: Double) -> String {
    (p.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(p)) : String(format: "%.2f", p)) + " ₽"
}

private func devicesWord(_ n: Int) -> String {
    let word: String
    if (11...14).contains(n % 100) { word = "устройств" }
    else if n % 10 == 1 { word = "устройство" }
    else if (2...4).contains(n % 10) { word = "устройства" }
    else { word = "устройств" }
    return "\(n) \(word)"
}

/// The payment page inside the app.
struct SafariView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }
    func updateUIViewController(_ vc: SFSafariViewController, context: Context) {}
}

extension URL: Identifiable {
    public var id: String { absoluteString }
}
