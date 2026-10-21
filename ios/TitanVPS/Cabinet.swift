import Foundation
import SwiftSoup

/// The site's limited personal cabinet, read natively (same as Android's CabinetApi): signed
/// in by the link the subscription brings (profile-web-page-url → /auth/magic/<portal token>),
/// the /cabinet page is parsed for the subscription and the tariffs; payments and payment
/// codes go to the endpoints the page's buttons use.
final class CabinetAPI {
    struct Tariff: Identifiable, Equatable {
        let kind: String
        let id: String
        let name: String
        let price: Double
        let limit: Int?
        let limitLabel: String
        let trafficLabel: String
        let methods: [String]
        let shown: Bool
    }

    struct Method: Equatable {
        let id: String
        let title: String
    }

    struct Snapshot: Equatable {
        var active = false
        var status = ""
        var days = ""
        var daysUnit = ""
        var hours = ""
        var heroText = ""
        var until = ""
        var traffic: String?
        var trafficPercent: Double?
        var trafficNote: String?
        var clientId: String?
        var deviceLimit: Int?
        var limits: [Int] = []
        var tariffs: [Tariff] = []
        var methods: [String: [Method]] = [:]
        var promo = false
    }

    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private let base: URL
    private let session: URLSession
    /// The cabinet's session cookie, kept by hand: URLSession's own cookie storage drops
    /// the one set on the sign-in redirect, so redirects are followed here as well.
    private var cookies: [String: String] = [:]
    private let lock = NSLock()

    private init(base: URL, session: URLSession) {
        self.base = base
        self.session = session
    }

    /// Signs in with the cabinet link from the subscription and reads the cabinet.
    static func open(_ link: String) async throws -> (CabinetAPI, Snapshot) {
        guard let url = URL(string: link), var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw Failure(message: "Нет ссылки на кабинет в подписке")
        }
        c.path = "/"; c.query = nil
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.timeoutIntervalForRequest = 20
        config.httpAdditionalHeaders = ["User-Agent": Config.userAgent]
        let api = CabinetAPI(base: c.url!, session: URLSession(configuration: config, delegate: NoRedirects(), delegateQueue: nil))
        return (api, try await api.page(url))
    }

    /// The site's host: PaymentView ends a payment when the provider sends it back here.
    var host: String? { base.host }

    func load() async throws -> Snapshot { try await page(base.appendingPathComponent("cabinet")) }

    func pay(_ tariff: Tariff, method: String) async throws -> URL {
        let path = tariff.kind == "traffic" ? "pay/traffic/\(tariff.id)" : "pay/\(tariff.id)"
        let json = try await post(path, field: "method", value: method)
        if json["ok"] as? Bool == true, let r = json["redirect"] as? String,
           let url = URL(string: r, relativeTo: base)?.absoluteURL { return url }
        throw Failure(message: (json["error"] as? String) ?? "Не удалось создать платёж")
    }

    func applyCode(_ code: String) async throws -> String {
        let json = try await post("pay/promo-code", field: "code", value: code)
        if json["ok"] as? Bool == true { return (json["message"] as? String) ?? "Код применён" }
        throw Failure(message: (json["error"] as? String) ?? "Не удалось применить код")
    }

    private func page(_ url: URL) async throws -> Snapshot {
        var req = URLRequest(url: url)
        req.setValue("text/html", forHTTPHeaderField: "Accept")
        let (body, http) = try await send(req)
        guard (200..<300).contains(http.statusCode), http.url?.path.hasPrefix("/cabinet") == true else {
            let path = http.url?.path ?? ""
            throw Failure(message: "Не удалось войти в кабинет (\(http.statusCode)\(path.isEmpty ? "" : ", \(path)")). Обновите подписку и попробуйте ещё раз")
        }
        return try Self.parse(String(decoding: body, as: UTF8.self))
    }

    private func post(_ path: String, field: String, value: String) async throws -> [String: Any] {
        let boundary = "titan-\(UUID().uuidString)"
        var req = URLRequest(url: base.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(field)\"\r\n\r\n\(value)\r\n--\(boundary)--\r\n".utf8)
        let (body, resp) = try await send(req)
        guard let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else {
            let code = resp.statusCode
            throw Failure(message: (200..<300).contains(code) ? "Неожиданный ответ сайта" : "Ошибка сайта (\(code))")
        }
        return json
    }

    /// Sends [request] with the session cookie, following redirects (a POST answered with
    /// 301/302/303 goes on as a GET) and keeping every cookie set on the way.
    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        var req = request
        for _ in 0..<10 {
            guard let url = req.url else { break }
            let header = lock.withLock { cookies.map { "\($0.key)=\($0.value)" }.joined(separator: "; ") }
            if !header.isEmpty { req.setValue(header, forHTTPHeaderField: "Cookie") }
            let (body, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse else { throw Failure(message: "Нет ответа от сайта") }
            let fields = http.allHeaderFields.reduce(into: [String: String]()) { d, kv in
                if let k = kv.key as? String, let v = kv.value as? String { d[k] = v }
            }
            let set = HTTPCookie.cookies(withResponseHeaderFields: fields, for: url)
            lock.withLock {
                for c in set {
                    if let exp = c.expiresDate, exp < Date() { cookies[c.name] = nil } else { cookies[c.name] = c.value }
                }
            }
            guard (300..<400).contains(http.statusCode),
                  let location = http.value(forHTTPHeaderField: "Location"),
                  let next = URL(string: location, relativeTo: url)?.absoluteURL else { return (body, http) }
            var follow = URLRequest(url: next)
            follow.setValue(req.value(forHTTPHeaderField: "Accept"), forHTTPHeaderField: "Accept")
            if http.statusCode == 307 || http.statusCode == 308 {
                follow.httpMethod = req.httpMethod
                follow.httpBody = req.httpBody
                follow.setValue(req.value(forHTTPHeaderField: "Content-Type"), forHTTPHeaderField: "Content-Type")
            }
            req = follow
        }
        throw Failure(message: "Слишком много перенаправлений")
    }

    static func parse(_ html: String) throws -> Snapshot {
        let doc = try SwiftSoup.parse(html)
        func text(_ q: String) -> String? { try? doc.select(q).first()?.text().trimmingCharacters(in: .whitespaces) }
        var d = Snapshot()
        d.active = (try? doc.select(".cab-pass.is-live").first()) != nil
        d.status = text(".cab-pass-status") ?? ""
        if let num = try? doc.select(".cab-pass-num").first() {
            d.days = num.ownText().trimmingCharacters(in: .whitespaces)
            d.daysUnit = (try? num.select(".u").first()?.text()) ?? ""
            d.hours = (try? num.select(".h").first()?.text()) ?? ""
        }
        d.heroText = text(".cab-pass-hero") ?? ""
        d.until = text(".cab-pass-until") ?? ""
        d.traffic = text(".cab-pass-stat-pct")
        if let style = try? doc.select(".cab-pass-bar-fill").first()?.attr("style"),
           let r = style.range(of: #"width:\s*([0-9.]+)%"#, options: .regularExpression) {
            d.trafficPercent = Double(style[r].filter { "0123456789.".contains($0) })
        }
        d.trafficNote = text(".cab-pass-traffic-note")
        d.clientId = text("#client-id-label")
        let scripts = ((try? doc.select("script").array()) ?? []).map { $0.data() }.joined(separator: "\n")
        if let re = try? NSRegularExpression(pattern: #"_cabLimitIp\s*=\s*(\d+)"#),
           let m = re.matches(in: scripts, range: NSRange(scripts.startIndex..., in: scripts)).last,
           let r = Range(m.range(at: 1), in: scripts), let n = Int(scripts[r]), n > 0 {
            d.deviceLimit = n
        }
        if let raw = try? doc.select(".limit-slider[data-limits]").first()?.attr("data-limits") {
            d.limits = raw.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        }
        for b in (try? doc.select("button.tariff-pick").array()) ?? [] {
            guard let tid = try? b.attr("data-tid"), !tid.isEmpty else { continue }
            let name = (try? b.attr("data-name")).flatMap { $0.isEmpty ? nil : $0 } ?? ((try? b.select(".tariff-pick-name").first()?.text()) ?? "")
            d.tariffs.append(Tariff(
                kind: (try? b.attr("data-kind")).flatMap { $0.isEmpty ? nil : $0 } ?? "sub",
                id: tid,
                name: name,
                price: Double((try? b.attr("data-price")) ?? "") ?? 0,
                limit: Int((try? b.attr("data-limit")) ?? ""),
                limitLabel: (try? b.attr("data-limit-label")) ?? "",
                trafficLabel: (try? b.attr("data-traffic-label")) ?? "",
                methods: ((try? b.attr("data-methods")) ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty },
                shown: !((try? b.attr("style")) ?? "").replacingOccurrences(of: " ", with: "").contains("display:none")
            ))
        }
        for kind in ["sub", "traffic"] {
            d.methods[kind] = ((try? doc.select("#payMethodsList-\(kind) [data-pm]").array()) ?? []).map { m in
                let marks = ((try? m.select("img.pay-mark").array()) ?? []).compactMap { img -> String? in
                    if img.hasClass("pay-mark-sbp") { return "СБП" }
                    if img.hasClass("pay-mark-mir") { return "МИР" }
                    if img.hasClass("pay-mark-btc") { return "Крипта" }
                    return nil
                }
                let id = (try? m.attr("data-pm")) ?? ""
                let fallback = ((try? m.text()) ?? "").trimmingCharacters(in: .whitespaces)
                return Method(id: id, title: marks.isEmpty ? (fallback.isEmpty ? id : fallback) : marks.joined(separator: " · "))
            }
        }
        d.promo = (try? doc.select("#promoCodeInput").first()) != nil
        return d
    }
}

/// Hands every redirect back to CabinetAPI.send, which keeps the cookies set on it.
private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// The bot's public summary of a subscription (GET /api/sub/{uuid}/info on the subscription's
/// own host): the devices that used the key and their limit. Same as Android's AccountApi.
enum AccountAPI {
    struct Device: Identifiable {
        let id = UUID()
        let os: String
        let osVersion: String
        let model: String
        let app: String
        let lastSeen: String
    }

    struct Info {
        let status: String
        let devices: [Device]
        let deviceLimit: Int
        let devicesEnabled: Bool
    }

    /// "Titan(IOS/0.2.29)" → "Titan VPS · iOS 0.2.29"; "Happ/5.9.0/ios/…" → "Happ 5.9.0".
    static func appName(_ userAgent: String) -> String {
        if let m = userAgent.range(of: #"^Titan\(([^/)]+)/([^)]*)\)"#, options: .regularExpression) {
            let inner = userAgent[m].dropFirst(6).dropLast()
            let parts = inner.split(separator: "/", maxSplits: 1).map(String.init)
            let platform = ["ios": "iOS", "android": "Android", "windows": "Windows"][parts[0].lowercased()] ?? parts[0]
            return "Titan VPS · \(platform)" + (parts.count > 1 && !parts[1].isEmpty ? " \(parts[1])" : "")
        }
        let parts = userAgent.split(separator: "/").map { $0.trimmingCharacters(in: .whitespaces) }
        guard let name = parts.first, !name.isEmpty else { return "Неизвестное приложение" }
        if name.lowercased() == "titan" { return "Titan VPS" }
        if parts.count > 1, parts[1].first?.isNumber == true { return "\(name) \(parts[1])" }
        return name
    }

    static func fetch(_ subscriptionUrl: String) async -> Info? {
        guard let re = try? NSRegularExpression(pattern: "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
              let m = re.matches(in: subscriptionUrl, range: NSRange(subscriptionUrl.startIndex..., in: subscriptionUrl)).last,
              let r = Range(m.range, in: subscriptionUrl) else { return nil }
        let uuid = String(subscriptionUrl[r])
        let hosts = ([URL(string: subscriptionUrl)?.host].compactMap { $0 } + Config.hosts.sorted()).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        for host in hosts {
            guard let url = URL(string: "https://\(host)/api/sub/\(uuid)/info") else { continue }
            var req = URLRequest(url: url, timeoutInterval: 15)
            req.setValue(Config.userAgent, forHTTPHeaderField: "User-Agent")
            guard let (body, resp) = try? await URLSession.shared.data(for: req),
                  let code = (resp as? HTTPURLResponse)?.statusCode else { continue }
            if code == 404 { return nil }
            guard (200..<300).contains(code), let o = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { continue }
            let d = o["devices"] as? [String: Any]
            let items = (d?["items"] as? [[String: Any]] ?? []).map {
                Device(os: $0["os"] as? String ?? "", osVersion: $0["osVersion"] as? String ?? "", model: $0["model"] as? String ?? "",
                       app: $0["userAgent"] as? String ?? "", lastSeen: $0["accessTime"] as? String ?? "")
            }
            return Info(status: o["userStatus"] as? String ?? "ACTIVE", devices: items,
                        deviceLimit: d?["limit"] as? Int ?? 0, devicesEnabled: d?["enabled"] as? Bool ?? !items.isEmpty)
        }
        return nil
    }
}
