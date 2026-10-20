import CryptoKit
import Foundation
import Network
import SwiftUI
import UIKit

/// Технический режим: a hidden diagnostics screen for support (same on every platform).
/// Opened by 7 taps on the version in Настройки and a code, checked by its hash only.
enum TechMode {
    static let key = "techMode"
    private static let codeHash = "bc060f10652adaefa8eaed330239a6e27ed27b65dceb56f55b000e1c73bcd667"

    static func check(_ code: String) -> Bool {
        let digest = SHA256.hash(data: Data("titan-tech:\(code.trimmingCharacters(in: .whitespaces))".utf8))
        return digest.map { String(format: "%02x", $0) }.joined() == codeHash
    }
}

/// The network as the system sees it: Wi-Fi / mobile / Ethernet and whether a VPN is up.
@MainActor
final class NetworkWatch: ObservableObject {
    @Published private(set) var kind = "…"
    @Published private(set) var tunnel = false
    @Published private(set) var interfaces = ""
    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let kind = Self.kind(path)
            let tunnel = path.usesInterfaceType(.other) || Self.systemVPN()
            let interfaces = path.availableInterfaces.map(\.name).joined(separator: ", ")
            Task { @MainActor in
                self?.kind = kind
                self?.tunnel = tunnel
                self?.interfaces = interfaces
            }
        }
        monitor.start(queue: DispatchQueue(label: "titan.techmode.path"))
    }

    deinit { monitor.cancel() }

    nonisolated static func kind(_ path: NWPath) -> String {
        guard path.status == .satisfied else { return "нет сети" }
        let names: [(NWInterface.InterfaceType, String)] = [(.wifi, "Wi‑Fi"), (.cellular, "мобильная"), (.wiredEthernet, "Ethernet")]
        if let hit = names.first(where: { path.usesInterfaceType($0.0) }) { return hit.1 }
        // Through a VPN the path is the tunnel; the network under it is among the available ones.
        for i in path.availableInterfaces {
            if let hit = names.first(where: { $0.0 == i.type }) { return hit.1 }
        }
        return "другая"
    }

    /// Any VPN (ours or another app's): its interface shows up in the scoped proxy settings.
    nonisolated static func systemVPN() -> Bool {
        guard let settings = CFNetworkCopySystemProxySettings()?.takeRetainedValue() as? [String: Any],
              let scoped = settings["__SCOPED__"] as? [String: Any] else { return false }
        return scoped.keys.contains { key in ["utun", "ipsec", "ppp", "tap", "tun"].contains { key.hasPrefix($0) } }
    }
}

private struct TCPTarget {
    let key: String
    let host: String
    let port: Int
}

/// The checks themselves: HTTPS reachability, the external IP, which servers are UDP.
enum TechChecks {
    static let sites = [
        "https://api1.titanvps.su", "https://api2.titanvps.online",
        "https://github.com", "https://t.me",
        "https://www.google.com", "https://www.youtube.com", "https://www.instagram.com",
        "https://ya.ru", "https://vk.com", "https://www.gosuslugi.ru",
    ]

    /// The answer itself is what we want, not where it redirects to.
    private final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 6
        config.timeoutIntervalForResource = 8
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }()

    /// "HTTP 200 · 85 мс" or why it failed.
    static func probe(_ url: String) async -> String {
        guard let u = URL(string: url) else { return "неверный адрес" }
        var req = URLRequest(url: u, timeoutInterval: 6)
        req.httpMethod = "HEAD"
        req.setValue(Config.userAgent, forHTTPHeaderField: "User-Agent")
        let start = Date()
        do {
            let (_, resp) = try await session.data(for: req)
            let ms = Int(Date().timeIntervalSince(start) * 1000)
            return "HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0) · \(ms) мс"
        } catch {
            return describe(error)
        }
    }

    /// "1.2.3.4 · RU" from Cloudflare's trace (ip= and loc=).
    static func externalIP() async -> String {
        var last = ""
        for url in ["https://1.1.1.1/cdn-cgi/trace", "https://www.cloudflare.com/cdn-cgi/trace"] {
            guard let u = URL(string: url) else { continue }
            do {
                let (data, _) = try await session.data(for: URLRequest(url: u, timeoutInterval: 6))
                var fields: [String: String] = [:]
                for line in String(decoding: data, as: UTF8.self).split(separator: "\n") {
                    let kv = line.split(separator: "=", maxSplits: 1)
                    if kv.count == 2 { fields[String(kv[0])] = String(kv[1]) }
                }
                if let ip = fields["ip"] { return ip + (fields["loc"].map { " · \($0)" } ?? "") }
                last = "в ответе нет ip="
            } catch {
                last = describe(error)
            }
        }
        return "не удалось: \(last)"
    }

    static func describe(_ error: Error) -> String {
        guard let e = error as? URLError else { return error.localizedDescription }
        let what: String
        switch e.code {
        case .timedOut: what = "таймаут"
        case .cannotFindHost, .dnsLookupFailed: what = "DNS: адрес не найден"
        case .cannotConnectToHost: what = "не удалось подключиться"
        case .networkConnectionLost: what = "соединение оборвалось"
        case .notConnectedToInternet: what = "нет интернета"
        case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
             .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot, .clientCertificateRejected:
            what = "ошибка TLS"
        default: what = e.localizedDescription
        }
        return "\(what) (\(e.code.rawValue))"
    }

    /// Protocols over UDP: a TCP connect says nothing about them.
    static func isUDP(_ s: Server) -> Bool {
        let p = s.proto.lowercased()
        if ["hysteria", "hysteria2", "tuic", "wireguard"].contains(p) || s.name.lowercased().contains("hysteria") { return true }
        guard let ep = Plugins.endpoint(s) else { return false }
        return ep.kind == .tuic || (ep.kind == .masque && !ep.http2)
    }
}

/// What Xray's TCP Fast Open dials did in one process (the tunnel's, or the app's own for
/// pings), by the kernel's record of each connection (TCP_CONNECTION_INFO, xray-tfo patch).
struct TFOStats {
    private let n: [String: Int]
    let recent: [String]

    init?(_ obj: [String: Any]?) {
        guard let obj, obj["dials"] != nil else { return nil }
        n = obj.compactMapValues { $0 as? Int }
        recent = obj["recent"] as? [String] ?? []
    }

    /// The SYN data limits of iOS's TCP (default MSS less options, before the handshake).
    var limit: String {
        "до \(n["synLimitIPv4"] ?? 0) Б (IPv4), \(n["synLimitIPv6"] ?? 0) Б (IPv6) данных в SYN — ограничение ядра iOS; остальное идёт после рукопожатия"
    }

    var summary: String {
        let c = { (key: String) in n[key] ?? 0 }
        let verdict: String
        if c("synDataAcked") > 0 {
            verdict = "TFO работает: данные ушли в SYN и сервер их принял"
        } else if c("dials") > 0 {
            verdict = "TFO запрошен, но ни разу данные в SYN не были приняты"
        } else {
            verdict = "TFO-подключений не было"
        }
        var lines = [
            verdict,
            "TFO-подключений: \(c("dials"))",
            "  данные в SYN приняты сервером: \(c("synDataAcked"))",
            "  данные в SYN не приняты (повторены после рукопожатия): \(c("synDataLost"))",
            "  cookie ещё не было, данные после рукопожатия: \(c("cookieOnly"))",
            "  ядро отправило обычный SYN: \(c("kernelPlain"))",
            "Без TFO: \(c("plain")) (TFO к серверу недавно не прошёл), \(c("notSafe")) (данные нельзя повторять)",
            "Ошибок TFO-подключения: \(c("failed"))",
        ]
        if c("unchecked") > 0 { lines.append("Без записи ядра: \(c("unchecked"))") }
        return lines.joined(separator: "\n")
    }

    static func app() -> TFOStats? { TFOStats(try? Titan.invoke(["method": "tfoStats"])) }
}

@MainActor
struct TechModeView: View {
    @EnvironmentObject var store: AppStore
    @EnvironmentObject var vpn: VPNController
    @Environment(\.dismiss) private var dismiss
    @AppStorage(TechMode.key) private var techMode = false
    @StateObject private var net = NetworkWatch()

    @State private var ip: String?
    @State private var checkingIP = false
    @State private var access: [String: String] = [:]
    @State private var checkingAccess = false
    @State private var accessRoute: String?
    @State private var tcp: [String: Int] = [:]
    @State private var checkingServers = false
    @State private var tcpNote: String?
    @State private var udp: Set<String> = []
    @State private var tfoTunnel: TFOStats?
    @State private var tfoApp: TFOStats?
    @State private var copied = false

    private static let stampFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "dd.MM.yyyy HH:mm:ss ZZZZZ"
        return f
    }()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                actions
                card("Устройство", deviceRows)
                card("Сеть", networkRows) {
                    Button { Task { await checkIP() } } label: { Text("Узнать внешний IP").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).disabled(checkingIP)
                }
                card("Обходы", bypassRows)
                card("Доступ", accessRows,
                     caption: "Запросы идут \(accessRoute ?? appRoute). HEAD, таймаут 6 с, без перехода по редиректам.") {
                    Button { Task { await checkAccess() } } label: { Text("Проверить доступ").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).disabled(checkingAccess)
                }
                card("Серверы", serverRows, caption: serverCaption) {
                    Button { Task { await checkServers() } } label: { Text("Проверить серверы").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).disabled(checkingServers)
                }
                card("TCP Fast Open", tfoRows,
                     caption: "По записи ядра iOS о каждом соединении (TCP_CONNECTION_INFO), а не по настройкам: «приняты сервером» — данные действительно ушли в SYN. TFO включается для серверов с «tfo» в имени.") {
                    Button { Task { await refreshTFO() } } label: { Text("Обновить").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                }
                journalCard
            }
            .padding(16)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.background.ignoresSafeArea())
        .navigationTitle("Технический режим")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: store.subscription?.fetchedAt) {
            udp = Set((store.subscription?.servers ?? []).filter(TechChecks.isUDP).map(\.id))
        }
        .task { await refreshTFO() }
    }

    private var actions: some View {
        VStack(spacing: 10) {
            Button { Task { await checkAll() } } label: {
                Label("Проверить всё", systemImage: "stethoscope").font(.system(size: 17, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .disabled(checkingIP || checkingAccess || checkingServers)
            Button {
                UIPasteboard.general.string = report
                copied = true
            } label: {
                Label(copied ? "Отчёт скопирован" : "Копировать отчёт", systemImage: copied ? "checkmark" : "doc.on.doc")
                    .frame(maxWidth: .infinity, minHeight: 40)
            }
            .buttonStyle(.bordered)
            Button(role: .destructive) {
                techMode = false
                dismiss()
            } label: {
                Text("Выключить технический режим").frame(maxWidth: .infinity, minHeight: 40)
            }
            .buttonStyle(.bordered)
        }
    }

    // MARK: - Sections (the same rows go into the copied report)

    private var deviceRows: [(String, String)] {
        let info = Bundle.main.infoDictionary
        let version = "\(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))"
        var rows: [(String, String)] = [
            ("Версия", version),
            ("Платформа", "iOS"),
            ("Система", "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"),
            ("Модель", SubscriptionAPI.deviceModel()),
            ("User-Agent", Config.userAgent),
            ("VPN", vpnText),
        ]
        if let s = store.selected {
            let plugin = Plugins.endpoint(s).map { " · \($0.kind.title)" } ?? ""
            rows.append(("Сервер", s.name))
            rows.append(("Протокол", (s.proto.isEmpty ? "?" : s.proto) + plugin))
            rows.append(("Адрес", "\(s.host ?? "?"):\(s.port.map { String($0) } ?? "?")"))
        } else {
            rows.append(("Сервер", "не выбран"))
        }
        if let sub = store.subscription {
            let i = sub.info
            let active = i.expire <= 0 || i.expire > Int64(Date().timeIntervalSince1970)
            let until = i.expire > 0 ? "до " + stamp(Date(timeIntervalSince1970: TimeInterval(i.expire))) : "бессрочно"
            rows.append(("Подписка", (active ? "активна, " : "истекла, ") + until))
            rows.append(("Трафик обходов", i.total > 0 ? "\(formatBytes(i.remaining)) из \(formatBytes(i.total))" : "безлимит"))
            rows.append(("Подписка обновлена", stamp(sub.fetchedAt)))
        } else {
            rows.append(("Подписка", "нет"))
        }
        rows.append(("Время", stamp(Date())))
        return rows
    }

    private var networkRows: [(String, String)] {
        let system: String
        if net.tunnel { system = vpn.isOn ? "есть (наш)" : "есть (сторонний)" } else { system = vpn.isOn ? "наш поднимается" : "нет" }
        return [
            ("Подключение", net.kind),
            ("Интерфейсы", net.interfaces.isEmpty ? "—" : net.interfaces),
            ("VPN в системе", system),
            ("Запросы приложения", appRoute
                + (vpn.isOn ? " (на iOS туннель забирает весь трафик, приложение из него не исключено)" : "")),
            ("Внешний IP", ip ?? "не проверялся"),
        ]
    }

    private var bypassRows: [(String, String)] {
        let all = store.subscription?.servers ?? []
        var rows: [(String, String)] = [
            ("Обходы сейчас", store.onWifi ? "доступны на Wi‑Fi (технический режим)" : "доступны — Wi‑Fi нет"),
            ("Серверов-обходов", "\(all.filter(ServerGroup.isBypass).count) из \(all.count)"),
        ]
        if let s = store.selected {
            rows.append(("Выбран обход", !ServerGroup.isBypass(s) ? "нет" : store.blockedOnWifi(s) ? "да — заблокирован на Wi‑Fi" : "да"))
        }
        rows.append(("Автопереключение", "с обхода при появлении Wi‑Fi — на «\(store.firstRegular?.name ?? "—")»"))
        return rows
    }

    private var serverCaption: String {
        let route: String
        if let tcpNote { route = "Проверка: \(tcpNote)." } else { route = vpn.isOn ? "На время проверки VPN выключится, как при пинге." : "" }
        return "«нет TCP» — IP или порт заблокирован, либо сервер лежит. TCP есть, а пинг не проходит — протокол режет DPI. " + route
    }

    private var accessRows: [(String, String)] {
        TechChecks.sites.map { url in
            (url.replacingOccurrences(of: "https://", with: ""), access[url] ?? (checkingAccess ? "…" : "—"))
        }
    }

    private var serverRows: [(String, String)] {
        (store.subscription?.servers ?? []).map { (s: Server) -> (String, String) in
            let value: String
            if udp.contains(s.id) {
                value = "UDP — TCP-проверка не показательна"
            } else if let host = s.host, let port = s.port {
                if let ms = tcp[Self.key(host, port)] {
                    value = ms >= 0 ? "\(ms) мс" : "нет TCP"
                } else {
                    value = checkingServers ? "…" : "—"
                }
            } else {
                value = "нет адреса"
            }
            return (s.name, value)
        }
    }

    private var tfoRows: [(String, String)] {
        var rows: [(String, String)] = [("Ограничение", (tfoTunnel ?? tfoApp)?.limit ?? "—")]
        rows.append(("Туннель", tfoTunnel?.summary ?? (vpn.isOn ? "туннель не ответил" : "VPN выключен")))
        if let recent = tfoTunnel?.recent, !recent.isEmpty {
            rows.append(("Туннель: последние соединения", recent.joined(separator: "\n")))
        }
        rows.append(("Пинг (приложение)", tfoApp?.summary ?? "нет данных"))
        if let recent = tfoApp?.recent, !recent.isEmpty {
            rows.append(("Пинг: последние соединения", recent.joined(separator: "\n")))
        }
        return rows
    }

    /// Why the last pings failed (Xray's errors; TCP Fast Open notes come in [brackets]) and the VPN's own error.
    private var journal: [String] {
        var lines: [String] = []
        if case .error(let e) = vpn.state { lines.append("VPN: \(e)") }
        let servers = store.subscription?.servers ?? []
        for s in servers {
            if let e = store.pingErrors[s.id] { lines.append("\(s.name): \(e)") }
        }
        return Array(lines.suffix(200))
    }

    private var journalCard: some View {
        Card {
            VStack(alignment: .leading, spacing: 8) {
                Text("Журнал").fontWeight(.semibold)
                let lines = journal
                Text(lines.isEmpty ? "Пусто: ошибок пинга нет" : lines.joined(separator: "\n"))
                    .font(.system(size: 12, weight: .regular, design: .monospaced))
                    .textSelection(.enabled)
                Text("Последние ошибки пинга серверов (заметки TCP Fast Open — в [скобках]) и ошибка подключения VPN.")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
            .padding(16)
        }
    }

    private func card(_ title: String, _ rows: [(String, String)], caption: String? = nil) -> some View {
        card(title, rows, caption: caption) { EmptyView() }
    }

    private func card<Extra: View>(_ title: String, _ rows: [(String, String)], caption: String? = nil,
                                   @ViewBuilder extra: () -> Extra) -> some View {
        Card {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).fontWeight(.semibold)
                ForEach(Array(rows.enumerated()), id: \.offset) { item in
                    VStack(alignment: .leading, spacing: 1) {
                        Text(item.element.0).font(.system(size: 12)).foregroundStyle(.secondary)
                        Text(item.element.1).font(.system(size: 13, weight: .regular, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                if let caption { Text(caption).font(.system(size: 12)).foregroundStyle(.secondary) }
                extra()
            }
            .padding(16)
        }
    }

    // MARK: - Report

    private var report: String {
        var out = "Titan VPS — технический режим\n"
        func add(_ title: String, _ rows: [(String, String)]) {
            out += "\n== \(title) ==\n"
            for (label, value) in rows { out += "\(label): \(value)\n" }
        }
        add("Устройство", deviceRows)
        add("Сеть", networkRows)
        add("Обходы", bypassRows)
        add("Доступ (\(accessRoute ?? appRoute))", accessRows)
        add("Серверы" + (tcpNote.map { " (\($0))" } ?? ""), serverRows)
        add("TCP Fast Open", tfoRows)
        out += "\n== Журнал ==\n" + (journal.isEmpty ? "пусто" : journal.joined(separator: "\n")) + "\n"
        return out
    }

    // MARK: - Checks

    /// Where the app's own requests go now.
    private var appRoute: String {
        if vpn.isOn { return "через наш VPN" }
        return net.tunnel ? "через сторонний VPN" : "напрямую"
    }

    private var vpnText: String {
        switch vpn.state {
        case .off: return "выключен"
        case .connecting: return "подключается"
        case .connected(let since): return "подключён с \(stamp(since))"
        case .disconnecting: return "отключается"
        case .error(let e): return "ошибка: \(e)"
        }
    }

    private func stamp(_ date: Date) -> String { Self.stampFormat.string(from: date) }

    private static func key(_ host: String, _ port: Int) -> String { "\(host):\(port)" }

    /// Network and access at once, then the servers (they may turn the VPN off for a while).
    private func checkAll() async {
        copied = false
        let ipDone = Task { await checkIP() }
        await checkAccess()
        await ipDone.value
        await refreshTFO()
        await checkServers()
    }

    /// The tunnel's TFO record (while the VPN is on) and the app's own, from its pings.
    private func refreshTFO() async {
        tfoTunnel = TFOStats(await vpn.tunnelTFOStats())
        tfoApp = TFOStats.app()
    }

    private func checkIP() async {
        guard !checkingIP else { return }
        checkingIP = true
        ip = "…"
        ip = await TechChecks.externalIP()
        checkingIP = false
    }

    private func checkAccess() async {
        guard !checkingAccess else { return }
        checkingAccess = true
        access = [:]
        accessRoute = appRoute
        await withTaskGroup(of: (String, String).self) { group in
            for url in TechChecks.sites {
                group.addTask { (url, await TechChecks.probe(url)) }
            }
            for await (url, result) in group { access[url] = result }
        }
        checkingAccess = false
    }

    /// Plain TCP connects to every host:port of the subscription (each once), 16 at a time.
    /// Through our tunnel they'd measure the tunnel, so the VPN is off meanwhile (as for a ping).
    private func checkServers() async {
        guard !checkingServers else { return }
        checkingServers = true
        tcp = [:]
        var seen = Set<String>()
        var targets: [TCPTarget] = []
        for s in store.subscription?.servers ?? [] where !udp.contains(s.id) {
            guard let host = s.host, let port = s.port else { continue }
            let key = Self.key(host, port)
            if seen.insert(key).inserted { targets.append(TCPTarget(key: key, host: host, port: port)) }
        }
        if vpn.isOn {
            tcpNote = "VPN выключался на время проверки, TCP — напрямую"
            await store.withVPNPaused { await measure(targets) }
        } else {
            tcpNote = net.tunnel ? "через сторонний VPN" : "напрямую"
            await measure(targets)
        }
        checkingServers = false
    }

    private func measure(_ targets: [TCPTarget]) async {
        await withTaskGroup(of: (String, Int).self) { group in
            var rest = targets.makeIterator()
            for _ in 0..<16 {
                guard let t = rest.next() else { break }
                group.addTask { (t.key, await AppStore.tcpPing(host: t.host, port: t.port)) }
            }
            while let r = await group.next() {
                tcp[r.0] = r.1
                if let t = rest.next() {
                    group.addTask { (t.key, await AppStore.tcpPing(host: t.host, port: t.port)) }
                }
            }
        }
    }
}
