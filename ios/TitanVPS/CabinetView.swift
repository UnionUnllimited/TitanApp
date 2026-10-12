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

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Picker("", selection: $tab) {
                    Text("Подписка").tag(0)
                    Text("Оплатить").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                if let d = data {
                    ScrollView {
                        VStack(spacing: 14) {
                            if let note { Text(note).font(.system(size: 13)).foregroundStyle(Palette.primary) }
                            if let error { Text(error).font(.system(size: 13)).foregroundStyle(.red) }
                            if tab == 0 { subscription(d) } else { PayPane(data: d, onPay: pay, onCode: applyCode) }
                        }
                        .padding(16)
                        .frame(maxWidth: 640)
                        .frame(maxWidth: .infinity)
                    }
                    .refreshable { await load() }
                } else if loading {
                    ProgressView().padding(.top, 48)
                    Spacer()
                } else {
                    VStack(spacing: 12) {
                        Text(error ?? "Ошибка").multilineTextAlignment(.center).foregroundStyle(.secondary)
                        Button("Повторить") { Task { await load() } }.buttonStyle(.borderedProminent)
                    }
                    .padding(32)
                    Spacer()
                }
            }
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle("Личный кабинет")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise") }.disabled(loading)
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

    @ViewBuilder private func subscription(_ d: CabinetAPI.Snapshot) -> some View {
        let color = d.active ? Palette.green : .red
        Card {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Circle().fill(color).frame(width: 7, height: 7)
                    Text(d.status.isEmpty ? (d.active ? "Активна" : "Неактивна") : d.status)
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(color)
                }
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(color.opacity(0.14), in: Capsule())
                if !d.days.isEmpty {
                    HStack(alignment: .lastTextBaseline, spacing: 6) {
                        Text(d.days).font(.system(size: 52, weight: .black))
                        Text(d.daysUnit).font(.system(size: 18)).foregroundStyle(.secondary)
                        if !d.hours.isEmpty { Text(d.hours).font(.system(size: 22, weight: .semibold)).foregroundStyle(.secondary) }
                    }
                    Text("ОСТАЛОСЬ").font(.system(size: 11)).kerning(1.5).foregroundStyle(.secondary)
                } else if !d.heroText.isEmpty {
                    Text(d.heroText).font(.system(size: 18, weight: .semibold))
                }
                if !d.until.isEmpty { Text(d.until).font(.system(size: 14)) }
            }
            .padding(18)
        }
        if let traffic = d.traffic {
            Card {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("ТРАФИК").font(.system(size: 11)).kerning(1.5).foregroundStyle(.secondary)
                        Spacer()
                        Text(traffic).font(.system(size: 12, weight: .semibold))
                    }
                    ProgressView(value: min(max((d.trafficPercent ?? 0) / 100, 0), 1)).tint(Palette.green)
                    if let n = d.trafficNote { Text(n).font(.system(size: 12)).foregroundStyle(.secondary) }
                }
                .padding(18)
            }
        }
        let limit = d.deviceLimit ?? devices.flatMap { $0.limit > 0 ? $0.limit : nil }
        if limit != nil || devices != nil {
            Card {
                VStack(alignment: .leading, spacing: 6) {
                    Text("УСТРОЙСТВА").font(.system(size: 11)).kerning(1.5).foregroundStyle(.secondary)
                    Text(devicesText(count: devices?.count, limit: limit)).font(.system(size: 24, weight: .bold))
                    Text("Лимит: " + (limit.map(devicesWord) ?? "без ограничения")).font(.system(size: 14, weight: .medium))
                }
                .padding(18)
            }
        }
        if let id = d.clientId {
            Card {
                Button {
                    UIPasteboard.general.string = id
                    note = "ID скопирован"
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("ВАШ ID").font(.system(size: 11)).kerning(1.5).foregroundStyle(.secondary)
                            Text(id).font(.system(size: 16, weight: .semibold))
                        }
                        Spacer()
                        Image(systemName: "doc.on.doc").foregroundStyle(.secondary)
                    }
                    .padding(18)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
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
            if data.tariffs.isEmpty { Text("Сейчас оплата недоступна").foregroundStyle(.secondary) }
            // A payment code is rare: a quiet link at the bottom, the field in a dialog.
            if data.promo {
                Button("Есть код оплаты?") { codeOpen = true }
                    .font(.system(size: 13)).foregroundStyle(.secondary).padding(.vertical, 8)
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
            VStack(alignment: .leading, spacing: 10) {
                Text(kind == "sub" ? "Продлить подписку" : "Докупить трафик").font(.system(size: 16, weight: .semibold))
                if kind == "sub" && !data.limits.isEmpty {
                    Text("Лимит устройств — сколько одновременных подключений нужно. Фиксируется после первой оплаты.")
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(data.limits, id: \.self) { l in
                                Button("\(l)") { limit = l; picked[kind] = nil }
                                    .font(.system(size: 14, weight: .medium))
                                    .frame(minWidth: 40, minHeight: 34)
                                    .background(limit == l ? Palette.primary.opacity(0.18) : Palette.track, in: RoundedRectangle(cornerRadius: 10))
                                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(limit == l ? Palette.primary : .clear))
                                    .buttonStyle(.plain)
                            }
                        }
                    }
                }
                ForEach(visible) { t in
                    let on = selected?.id == t.id
                    Button { picked[kind] = t.id } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t.name).fontWeight(.medium)
                                let meta = [t.limitLabel, t.trafficLabel].filter { !$0.isEmpty }.joined(separator: " · ")
                                if !meta.isEmpty { Text(meta).font(.system(size: 12)).foregroundStyle(.secondary) }
                            }
                            Spacer()
                            Text(price(t.price)).fontWeight(.bold)
                        }
                        .padding(.horizontal, 14).frame(minHeight: 56)
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(on ? Palette.primary : Palette.outline, lineWidth: on ? 1.5 : 1))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if let t = selected {
                    let methods = (data.methods[kind] ?? []).filter { t.methods.contains($0.id) }
                    let list = methods.isEmpty ? t.methods.map { CabinetAPI.Method(id: $0, title: "") } : methods
                    if list.isEmpty { Text("Оплата для этого тарифа недоступна").font(.system(size: 13)).foregroundStyle(.red) }
                    ForEach(list, id: \.id) { m in
                        Button {
                            busy = true
                            Task { await onPay(t, m.id); busy = false }
                        } label: {
                            Group {
                                if busy { ProgressView().tint(.white) } else {
                                    Text("Оплатить \(price(t.price))" + (m.title.isEmpty ? "" : " · \(m.title)")).fontWeight(.semibold)
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: 50)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(busy)
                    }
                }
            }
            .padding(18)
        }
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
