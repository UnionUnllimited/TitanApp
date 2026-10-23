import Foundation

/// Проверить соединение (as in Happ): a request through the running VPN itself (the app's
/// own traffic goes into the tunnel), so it shows whether the connection works right now,
/// how long a fresh HTTPS request takes and which address the sites see.
enum ConnectionCheck {
    struct Result: Equatable {
        let ms: Int
        let ip: String?
        /// ISO code of the exit's country (Cloudflare's loc=).
        let country: String?
    }

    enum Status: Equatable {
        case idle, checking, ok(Result), failed(String)
    }

    static func run() async -> Status {
        // A new session each time: a new connection, as a ping measures it.
        let session = freshSession(timeout: 8)
        defer { session.finishTasksAndInvalidate() }
        async let exitInfo = exitAddress(session)
        guard let url = URL(string: XrayPing.url) else { return .failed("неверный адрес") }
        var why = "нет ответа"
        do {
            let ms = try await head(url, session, timeout: 8)
            let address = await exitInfo
            return .ok(Result(ms: ms, ip: address.ip, country: address.country))
        } catch {
            why = TechChecks.describe(error)
        }
        // Google didn't answer but Cloudflare did: the VPN carries traffic (the panel's
        // routing may send Google another way), so it isn't "no answer".
        let address = await exitInfo
        if let ms = address.ms, address.ip != nil {
            return .ok(Result(ms: ms, ip: address.ip, country: address.country))
        }
        return .failed(why)
    }

    /// The Live Activity's ping (LiveActivityController, at most once a minute): run's request
    /// alone, through the VPN on a fresh connection; nil if it didn't answer.
    static func probe() async -> Int? {
        guard let url = URL(string: XrayPing.url) else { return nil }
        let session = freshSession(timeout: 5)
        defer { session.finishTasksAndInvalidate() }
        return try? await head(url, session, timeout: 5)
    }

    /// No cache, no cookies: each request on a new connection.
    private static func freshSession(timeout: TimeInterval) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout + 2
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        return URLSession(configuration: config)
    }

    /// A HEAD of [url]: how long it took, in ms.
    private static func head(_ url: URL, _ session: URLSession, timeout: TimeInterval) async throws -> Int {
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = "HEAD"
        req.setValue(Config.userAgent, forHTTPHeaderField: "User-Agent")
        let start = Date()
        _ = try await session.data(for: req)
        return Int(Date().timeIntervalSince(start) * 1000)
    }

    /// The exit's address and country from Cloudflare's trace (ip= and loc=), and how long it took.
    private static func exitAddress(_ session: URLSession) async -> (ip: String?, country: String?, ms: Int?) {
        let start = Date()
        guard let url = URL(string: "https://1.1.1.1/cdn-cgi/trace"),
              let answer = try? await session.data(for: URLRequest(url: url, timeoutInterval: 8)) else { return (nil, nil, nil) }
        let ms = Int(Date().timeIntervalSince(start) * 1000)
        var fields: [String: String] = [:]
        for line in String(decoding: answer.0, as: UTF8.self).split(separator: "\n") {
            let kv = line.split(separator: "=", maxSplits: 1)
            if kv.count == 2 { fields[String(kv[0])] = String(kv[1]) }
        }
        return (fields["ip"], fields["loc"], ms)
    }
}
