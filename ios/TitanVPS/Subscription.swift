import Foundation
import UIKit

enum Config {
    static let hosts: Set<String> = ["api1.titanvps.su", "api2.titanvps.online"]
    static let telegram = URL(string: "https://t.me/TitanVPS_bot")!
    /// Same as Android: our own UA first, "Xray" if the panel doesn't answer it with Xray JSON.
    /// Our User-Agent everywhere: "Titan(IOS/<version>)" (Android and Windows the same way).
    static let userAgent = "Titan(IOS/\((Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0"))"
    static let userAgents = [userAgent, "Xray"]
}

struct SubscriptionInfo: Codable, Equatable {
    var upload: Int64 = 0
    var download: Int64 = 0
    /// 0 means unlimited.
    var total: Int64 = 0
    /// Unix seconds, 0 means no expiry.
    var expire: Int64 = 0
    var webPageUrl: String?
    var supportUrl: String?
    var announce: String?

    var used: Int64 { upload + download }
    var remaining: Int64 { max(total - used, 0) }
}

struct Server: Codable, Identifiable, Equatable {
    let id: String
    let name: String
    let host: String?
    let port: Int?
    let proto: String
    /// The server's whole Xray config from the subscription (routing, DNS… from the panel),
    /// inbounds replaced by ours when connecting. Nil only in subscriptions saved by old builds.
    var xrayJson: String? = nil
    /// Tag of the outbound that carries user traffic.
    var proxyTag: String? = nil
}

struct Subscription: Codable, Equatable {
    let url: String
    let info: SubscriptionInfo
    let servers: [Server]
    let fetchedAt: Date
}

struct SubscriptionError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Same rules as the Android app: only our hosts, fallback between them, Xray JSON.
enum SubscriptionAPI {

    static func isAllowed(_ string: String) -> Bool {
        guard let c = URLComponents(string: string), c.scheme?.lowercased() == "https",
              c.user == nil, let host = c.host?.lowercased(), Config.hosts.contains(host) else { return false }
        return !c.path.isEmpty && c.path != "/"
    }

    /// Finds our subscription URL in pasted text (plain, deep links, percent-encoded).
    static func findLink(_ text: String) -> String? {
        let hosts = Config.hosts.map { NSRegularExpression.escapedPattern(for: $0) }.joined(separator: "|")
        guard let re = try? NSRegularExpression(pattern: "https://(\(hosts))/[^\\s\"'<>\\\\`]+", options: .caseInsensitive) else { return nil }
        for t in [text, text.removingPercentEncoding ?? text] {
            let range = NSRange(t.startIndex..., in: t)
            for m in re.matches(in: t, range: range) {
                guard let r = Range(m.range, in: t) else { continue }
                let url = String(t[r]).trimmingCharacters(in: CharacterSet(charactersIn: ".,);&"))
                if isAllowed(url) { return url }
            }
        }
        return nil
    }

    static func fetch(_ url: String) async throws -> Subscription {
        guard isAllowed(url) else { throw SubscriptionError(message: "Недопустимый адрес подписки") }
        var firstError: Error?
        // Our own UA ("Titan(IOS/…)"); "Xray" if the server doesn't answer it with Xray JSON.
        for ua in Config.userAgents {
            for candidate in [url] + alternates(url) {
                do {
                    let (info, body) = try await download(candidate, userAgent: ua)
                    let all = (try? parseServers(body)) ?? []
                    if all.isEmpty && ua != Config.userAgents.last { continue }
                    if all.isEmpty { throw SubscriptionError(message: SubscriptionErrors.unusable(info, body)) }
                    if let stub = SubscriptionErrors.stub(all.map(\.name)) { throw SubscriptionError(message: stub) }
                    let servers = all.filter { !ServerGroup.isHidden($0) }
                    if servers.isEmpty { throw SubscriptionError(message: "В подписке нет серверов") }
                    return Subscription(url: url, info: info, servers: servers, fetchedAt: Date())
                } catch {
                    if firstError == nil { firstError = error }
                }
            }
        }
        throw firstError ?? SubscriptionError(message: "Сервер подписки недоступен")
    }

    private static func alternates(_ url: String) -> [String] {
        guard var c = URLComponents(string: url), let host = c.host?.lowercased() else { return [] }
        return Config.hosts.filter { $0 != host }.sorted().compactMap { h in c.host = h; return c.string }
    }

    private static func download(_ url: String, userAgent: String) async throws -> (SubscriptionInfo, String) {
        var req = URLRequest(url: URL(string: url)!, timeoutInterval: 20)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        req.setValue(hwid(), forHTTPHeaderField: "x-hwid")
        req.setValue("iOS", forHTTPHeaderField: "x-device-os")
        req.setValue(UIDevice.current.systemVersion, forHTTPHeaderField: "x-ver-os")
        req.setValue(deviceModel(), forHTTPHeaderField: "x-device-model")
        let data: Data, resp: URLResponse
        do {
            (data, resp) = try await URLSession.shared.data(for: req)
        } catch {
            throw SubscriptionError(message: "Нет соединения с сервером подписки")
        }
        guard let http = resp as? HTTPURLResponse else { throw SubscriptionError(message: "Сервер подписки недоступен") }
        let host = http.url?.host ?? ""
        if let final = http.url?.absoluteString, !isAllowed(final) { throw SubscriptionError(message: "Недопустимый адрес подписки") }
        if http.statusCode == 404 || http.statusCode == 403 {
            throw SubscriptionError(message: "Подписка не найдена или отключена (\(http.statusCode), \(host))")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw SubscriptionError(message: "Сервер подписки недоступен (\(http.statusCode), \(host))")
        }
        let header = { (name: String) in http.value(forHTTPHeaderField: name) }
        return (parseInfo(header), String(decoding: data, as: UTF8.self))
    }

    static func parseInfo(_ header: (String) -> String?) -> SubscriptionInfo {
        var info = SubscriptionInfo()
        for part in (header("subscription-userinfo") ?? "").split(separator: ";") {
            let kv = part.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            guard kv.count == 2, let v = Double(kv[1]) else { continue }
            switch kv[0].lowercased() {
            case "upload": info.upload = Int64(v)
            case "download": info.download = Int64(v)
            case "total": info.total = Int64(v)
            case "expire": info.expire = Int64(v)
            default: break
            }
        }
        info.webPageUrl = header("profile-web-page-url")?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        info.supportUrl = header("support-url")?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        info.announce = decodeText(header("announce"))
        return info
    }

    private static func decodeText(_ value: String?) -> String? {
        guard let v = value?.trimmingCharacters(in: .whitespaces), !v.isEmpty else { return nil }
        guard v.hasPrefix("base64:") else { return v }
        let b64 = String(v.dropFirst("base64:".count)).trimmingCharacters(in: .whitespaces)
        guard let d = Data(base64Encoded: b64) else { return nil }
        return String(data: d, encoding: .utf8)?.nilIfEmpty
    }

    /// TCP Fast Open, as on Android: hosts whose name (or Host Mapper "titan" field) says
    /// "tfo" dial with TFO.
    static func applyTfo(_ name: String, _ proxy: inout [String: Any]) {
        guard name.lowercased().contains("tfo") || ((proxy["titan"] as? String) ?? "").lowercased().contains("tfo") else { return }
        var stream = proxy["streamSettings"] as? [String: Any] ?? [:]
        var sockopt = stream["sockopt"] as? [String: Any] ?? [:]
        sockopt["tcpFastOpen"] = true
        stream["sockopt"] = sockopt
        proxy["streamSettings"] = stream
    }

    /// iOS: a server marked "pktup" in its name (or Host Mapper "titan" field) uploads in XHTTP
    /// packet-up over TFO. Each upload is then its own request that fits a SYN whole (fitXhttpInSyn
    /// caps it), so no upload data trails the handshake in the open — which some mobile DPI drops on
    /// certain nodes, where Android still gets through only because its larger SYN hides the whole
    /// handshake. The server inbound must be "auto" or "packet-up"; a stream-up-only inbound answers
    /// packet-up with 400. Marker-gated, so the stream-up production servers are untouched.
    static func applyPacketUp(_ name: String, _ outbound: inout [String: Any]) {
        guard name.lowercased().contains("pktup") || ((outbound["titan"] as? String) ?? "").lowercased().contains("pktup"),
              var stream = outbound["streamSettings"] as? [String: Any],
              ["xhttp", "splithttp"].contains((stream["network"] as? String) ?? "") else { return }
        // Packet-up rides a SYN only over TFO.
        var sockopt = stream["sockopt"] as? [String: Any] ?? [:]
        sockopt["tcpFastOpen"] = true
        stream["sockopt"] = sockopt
        let key = stream["xhttpSettings"] != nil ? "xhttpSettings" : "splithttpSettings"
        var xhttp = stream[key] as? [String: Any] ?? [:]
        xhttp["mode"] = "packet-up"
        if var extra = xhttp["extra"] as? [String: Any] { extra["mode"] = "packet-up"; xhttp["extra"] = extra }
        stream[key] = xhttp
        outbound["streamSettings"] = stream
    }

    /// iOS puts at most ~470 bytes in a TFO SYN (Android ~1400), and an XHTTP request with
    /// the default browser headers and padding is 700-1600: the rest of its plain HTTP
    /// headers went after the handshake, where the DPI box saw them. For XHTTP over TFO
    /// the request is cut down to fit: a short browser User-Agent and padding at the bottom
    /// of the server's range (the server checks only its length).
    /// Upload bytes per XHTTP POST over TFO on iOS (headers ~330 B + this ≤ ~470 B in a SYN).
    static let xhttpPostInSyn = 120

    /// A browser's User-Agent short enough for the SYN ("golang" gave the program away to
    /// the DPI box; Xray's default Chrome one is ~50 B longer).
    static let xhttpUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X)"

    static func fitXhttpInSyn(_ outbound: inout [String: Any]) {
        guard var stream = outbound["streamSettings"] as? [String: Any],
              ["xhttp", "splithttp"].contains((stream["network"] as? String) ?? ""),
              (stream["sockopt"] as? [String: Any])?["tcpFastOpen"] as? Bool == true else { return }
        let key = stream["xhttpSettings"] != nil ? "xhttpSettings" : "splithttpSettings"
        var xhttp = stream[key] as? [String: Any] ?? [:]
        func tighten(_ s: inout [String: Any]) {
            var headers = s["headers"] as? [String: Any] ?? [:]
            if !headers.keys.contains(where: { $0.lowercased() == "user-agent" }) { headers["User-Agent"] = xhttpUserAgent }
            s["headers"] = headers
            // Exactly the bottom of the server's range: the request size doesn't wander.
            let from = paddingFrom(s["xPaddingBytes"]) ?? 100
            s["xPaddingBytes"] = "\(from)-\(from)"
            // Uploads too: Android puts each POST with its data (up to ~1300 B) in a SYN;
            // here a POST that doesn't fit sent the rest after the handshake, and with it the
            // site's name from the TLS handshake inside, so the DPI box cut the connection.
            // Small POSTs fit with their headers (~330 B) in the ~470 B a SYN takes.
            s["scMaxEachPostBytes"] = xhttpPostInSyn
        }
        // "extra" overrides the top-level settings in Xray: both get the same.
        if var extra = xhttp["extra"] as? [String: Any] {
            if xhttp["xPaddingBytes"] == nil, let p = extra["xPaddingBytes"] { xhttp["xPaddingBytes"] = p }
            tighten(&extra)
            xhttp["extra"] = extra
        }
        tighten(&xhttp)
        stream[key] = xhttp
        outbound["streamSettings"] = stream
    }

    /// The lower end of an Xray range ("100-1000", 100, {"from": 100, ...}).
    private static func paddingFrom(_ value: Any?) -> Int? {
        switch value {
        case let n as Int: return n
        case let s as String: return Int(s.split(separator: "-").first.map(String.init) ?? s)
        case let d as [String: Any]: return d["from"] as? Int
        default: return nil
        }
    }

    /// Xray JSON (array of configs or one config): name from `remarks`, endpoint of the
    /// first proxy outbound for the TCP ping.
    static func parseServers(_ body: String) throws -> [Server] {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{FEFF}")))
        guard let data = text.data(using: .utf8), let json = try? JSONSerialization.jsonObject(with: data) else {
            throw SubscriptionError(message: "Подписка пришла не в формате Xray JSON")
        }
        let configs: [[String: Any]] = (json as? [[String: Any]]) ?? ((json as? [String: Any]).map { [$0] } ?? [])
        let service: Set<String> = ["freedom", "blackhole", "dns", "loopback"]
        return configs.enumerated().compactMap { i, source in
            var cfg = source
            guard var outbounds = cfg["outbounds"] as? [[String: Any]],
                  let proxyIndex = outbounds.firstIndex(where: { !service.contains(($0["protocol"] as? String) ?? "") }) else { return nil }
            // Remnawave may omit the tag; routing and ping need one.
            if ((outbounds[proxyIndex]["tag"] as? String) ?? "").isEmpty { outbounds[proxyIndex]["tag"] = "proxy" }
            let name = ((cfg["remarks"] as? String)?.nilIfEmpty) ?? "Сервер \(i + 1)"
            applyTfo(name, &outbounds[proxyIndex])
            applyPacketUp(name, &outbounds[proxyIndex])
            for i in outbounds.indices { fitXhttpInSyn(&outbounds[i]) }
            cfg["outbounds"] = outbounds
            let proxy = outbounds[proxyIndex]
            let proxyTag = proxy["tag"] as? String ?? "proxy"
            cfg.removeValue(forKey: "remarks")
            // Our TUN inbound replaces the config's inbounds (like on Android): rules bound to
            // their tags follow it, and their sniffing settings are kept.
            let inbounds = cfg["inbounds"] as? [[String: Any]] ?? []
            let oldTags = Set(inbounds.compactMap { ($0["tag"] as? String)?.nilIfEmpty })
            if let sniffing = inbounds.compactMap({ $0["sniffing"] as? [String: Any] }).first { cfg[XrayRunConfig.sniffingKey] = sniffing }
            cfg.removeValue(forKey: "inbounds")
            XrayRunConfig.retargetInboundRules(&cfg, oldTags: oldTags)
            let (host, port) = endpoint(proxy)
            // Sorted: a Swift dictionary's key order changes with each parse, and
            // VPNController.source hashes this to tell a changed server.
            let json = (try? JSONSerialization.data(withJSONObject: cfg, options: .sortedKeys)).map { String(decoding: $0, as: UTF8.self) }
            // Stable across launches (hashValue isn't): the picked server stays picked.
            return Server(id: "json-\(i)-\(name)", name: name, host: host, port: port,
                          proto: (proxy["protocol"] as? String) ?? "", xrayJson: json, proxyTag: proxyTag)
        }
    }

    private static func endpoint(_ ob: [String: Any]) -> (String?, Int?) {
        let s = ob["settings"] as? [String: Any] ?? [:]
        let first = (s["vnext"] as? [[String: Any]])?.first ?? (s["servers"] as? [[String: Any]])?.first ?? s
        let host = first["address"] as? String
        let port = (first["port"] as? Int) ?? Int((first["port"] as? String) ?? "")
        return (host, port)
    }

    private static func hwid() -> String {
        let key = "titan.hwid"
        if let v = UserDefaults.standard.string(forKey: key) { return v }
        let v = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        UserDefaults.standard.set(v, forKey: key)
        return v
    }

    static func deviceModel() -> String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return "Apple \(machine)"
    }
}

/// Серверы / Обходы, same rules as Android (data/ServerGroups.kt): "Обходы" are the servers
/// with "обход" in the name; section headers and the panel's info entries aren't servers.
enum ServerGroup: String, CaseIterable, Identifiable {
    case servers = "Серверы", bypass = "Обходы"
    var id: String { rawValue }

    static func split(_ servers: [Server]) -> [ServerGroup: [Server]] {
        let bypass = servers.filter(isBypass)
        let regular = servers.filter { !isBypass($0) }
        var out: [ServerGroup: [Server]] = [:]
        if !regular.isEmpty { out[.servers] = regular }
        if !bypass.isEmpty { out[.bypass] = bypass }
        return out
    }

    /// A tab's servers as Главная lists them: favourites first, in the subscription's order
    /// otherwise (the Live Activity's ‹ › go through them in this order too).
    static func ordered(_ servers: [Server], favorites: Set<String>) -> [Server] {
        servers.filter { favorites.contains($0.name) } + servers.filter { !favorites.contains($0.name) }
    }

    static func isBypass(_ s: Server) -> Bool { s.name.lowercased().contains("обход") }
    /// A bypass server that spends GB: all but the free ones ("Обход 1 бесплатный").
    static func isLimited(_ s: Server) -> Bool { isBypass(s) && !letters(s.name).contains("бесплатн") }

    /// The name's letters only, lower case, Latin look-alikes read as Cyrillic ("бecплaтный").
    private static func letters(_ name: String) -> String {
        let map: [Character: Character] = [
            "a": "а", "e": "е", "o": "о", "p": "р", "c": "с", "x": "х", "y": "у",
            "k": "к", "m": "м", "t": "т", "h": "н", "b": "в", "n": "п", "u": "и",
        ]
        return String(name.precomposedStringWithCompatibilityMapping.lowercased()
            .filter(\.isLetter).map { map[$0] ?? $0 })
    }

    /// Headers ("👇БЕЗЛИМИТНЫЕ👇", "ЛИМИТНЫЕ ОСТ: …") and notices with a dummy address.
    static func isHidden(_ s: Server) -> Bool {
        let n = s.name.lowercased()
        if n.contains("лимитн") || ["сервер для", "обновления подписки"].contains(where: n.contains) { return true }
        let host = (s.host ?? "").lowercased()
        return ["127.0.0.1", "0.0.0.0", "localhost", "::1"].contains(host) || (s.port.map { $0 <= 1 } ?? false)
    }
}

/// Why a subscription that answered can't be used, in words (same as Android).
enum SubscriptionErrors {
    private static let stubs = ["не поддерживается", "заблокирован", "лимит устройств", "превышен лимит", "истекл", "неактивн"]

    static func unusable(_ info: SubscriptionInfo, _ body: String) -> String {
        if let a = info.announce?.trimmingCharacters(in: .whitespacesAndNewlines), !a.isEmpty { return a }
        if body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "Сервер подписки не прислал ни одного сервера. Возможно, превышен лимит устройств или подписка неактивна — напишите в поддержку"
        }
        return "Не удалось разобрать подписку. Напишите в поддержку"
    }

    /// The stub's text when every entry is one (no real server came).
    static func stub(_ names: [String]) -> String? {
        guard !names.isEmpty, names.allSatisfy({ n in stubs.contains { n.lowercased().contains($0) } }) else { return nil }
        let text = names[0].trimmingCharacters(in: .whitespaces)
        return text.lowercased().contains("не поддерживается")
            ? "\(text). Чаще всего это значит, что превышен лимит устройств — удалите старое устройство или напишите в поддержку"
            : text
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }

    /// Leading flag emoji and the rest of the name: a country's, or England's, Scotland's and
    /// Wales' (🏴, tag letters, the cancel tag).
    var splitFlag: (String?, String) {
        let t = trimmingCharacters(in: .whitespaces)
        let scalars = Array(t.unicodeScalars)
        let regional: ClosedRange<UInt32> = 0x1F1E6...0x1F1FF
        var end = 0
        if scalars.count >= 2, regional.contains(scalars[0].value), regional.contains(scalars[1].value) {
            end = 2
        } else if scalars.first?.value == 0x1F3F4, let cancel = scalars.firstIndex(where: { $0.value == 0xE007F }),
                  cancel > 1, scalars[1..<cancel].allSatisfy({ (0xE0020...0xE007E).contains($0.value) }) {
            end = cancel + 1
        }
        guard end > 0 else { return (nil, t) }
        let flag = String(String.UnicodeScalarView(scalars[0..<end]))
        let rest = String(String.UnicodeScalarView(scalars[end...])).trimmingCharacters(in: .whitespaces)
        return (flag, rest)
    }
}

func formatBytes(_ bytes: Int64) -> String {
    let gb = Double(bytes) / 1024 / 1024 / 1024
    return gb >= 1 ? String(format: "%.1f ГБ", gb) : String(format: "%.0f МБ", Double(bytes) / 1024 / 1024)
}
