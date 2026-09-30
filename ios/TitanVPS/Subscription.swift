import Foundation
import UIKit

enum Config {
    static let hosts: Set<String> = ["api1.titanvps.su", "api2.titanvps.online"]
    static let telegram = URL(string: "https://t.me/TitanVPS_bot")!
    // TODO: temporary, same as Android. Switch to our own UA later.
    static let userAgent = "Xray"
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
        for candidate in [url] + alternates(url) {
            do {
                let (info, body) = try await download(candidate)
                let servers = try parseServers(body).filter { !$0.name.lowercased().contains("безлимит") }
                if servers.isEmpty { throw SubscriptionError(message: "В подписке нет серверов") }
                return Subscription(url: url, info: info, servers: servers, fetchedAt: Date())
            } catch {
                if firstError == nil { firstError = error }
            }
        }
        throw firstError ?? SubscriptionError(message: "Сервер подписки недоступен")
    }

    private static func alternates(_ url: String) -> [String] {
        guard var c = URLComponents(string: url), let host = c.host?.lowercased() else { return [] }
        return Config.hosts.filter { $0 != host }.sorted().compactMap { h in c.host = h; return c.string }
    }

    private static func download(_ url: String) async throws -> (SubscriptionInfo, String) {
        var req = URLRequest(url: URL(string: url)!, timeoutInterval: 20)
        req.setValue(Config.userAgent, forHTTPHeaderField: "User-Agent")
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

    /// Xray JSON (array of configs or one config): name from `remarks`, endpoint of the
    /// first proxy outbound for the TCP ping.
    static func parseServers(_ body: String) throws -> [Server] {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{FEFF}")))
        guard let data = text.data(using: .utf8), let json = try? JSONSerialization.jsonObject(with: data) else {
            throw SubscriptionError(message: "Подписка пришла не в формате Xray JSON")
        }
        let configs: [[String: Any]] = (json as? [[String: Any]]) ?? ((json as? [String: Any]).map { [$0] } ?? [])
        let service: Set<String> = ["freedom", "blackhole", "dns", "loopback"]
        return configs.enumerated().compactMap { i, cfg in
            guard let outbounds = cfg["outbounds"] as? [[String: Any]],
                  let proxy = outbounds.first(where: { !service.contains(($0["protocol"] as? String) ?? "") }) else { return nil }
            let name = ((cfg["remarks"] as? String)?.nilIfEmpty) ?? "Сервер \(i + 1)"
            let (host, port) = endpoint(proxy)
            return Server(id: "json-\(i)-\(name.hashValue)", name: name, host: host, port: port,
                          proto: (proxy["protocol"] as? String) ?? "")
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

    private static func deviceModel() -> String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return "Apple \(machine)"
    }
}

/// Серверы / Обходы, same rule as Android: everything from the "ЛИМИТНЫЕ" header or
/// the first "обход" server on is a bypass server.
enum ServerGroup: String, CaseIterable, Identifiable {
    case servers = "Серверы", bypass = "Обходы"
    var id: String { rawValue }

    static func split(_ servers: [Server]) -> [ServerGroup: [Server]] {
        let first = servers.firstIndex { s in
            let n = s.name.lowercased()
            return n.contains("обход") || (n.contains("лимитн") && !n.contains("безлимит"))
        }
        guard let first else { return [.servers: servers] }
        return [.servers: Array(servers[..<first]), .bypass: Array(servers[first...])]
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }

    /// Leading flag emoji and the rest of the name.
    var splitFlag: (String?, String) {
        let t = trimmingCharacters(in: .whitespaces)
        let scalars = Array(t.unicodeScalars)
        let regional: ClosedRange<UInt32> = 0x1F1E6...0x1F1FF
        if scalars.count >= 2, regional.contains(scalars[0].value), regional.contains(scalars[1].value) {
            let flag = String(String.UnicodeScalarView(scalars[0..<2]))
            let rest = String(String.UnicodeScalarView(scalars[2...])).trimmingCharacters(in: .whitespaces)
            return (flag, rest)
        }
        return (nil, t)
    }
}

func formatBytes(_ bytes: Int64) -> String {
    let gb = Double(bytes) / 1024 / 1024 / 1024
    return gb >= 1 ? String(format: "%.1f ГБ", gb) : String(format: "%.0f МБ", Double(bytes) / 1024 / 1024)
}
