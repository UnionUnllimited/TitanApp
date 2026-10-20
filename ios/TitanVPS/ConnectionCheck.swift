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
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 8
        config.timeoutIntervalForResource = 10
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        // A new session each time: a new connection, as a ping measures it.
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        async let exitInfo = exitAddress(session)
        guard let url = URL(string: XrayPing.url) else { return .failed("неверный адрес") }
        var req = URLRequest(url: url, timeoutInterval: 8)
        req.httpMethod = "HEAD"
        req.setValue(Config.userAgent, forHTTPHeaderField: "User-Agent")
        let start = Date()
        do {
            _ = try await session.data(for: req)
            let ms = Int(Date().timeIntervalSince(start) * 1000)
            let address = await exitInfo
            return .ok(Result(ms: ms, ip: address.ip, country: address.country))
        } catch {
            return .failed(TechChecks.describe(error))
        }
    }

    /// The exit's address and country from Cloudflare's trace (ip= and loc=).
    private static func exitAddress(_ session: URLSession) async -> (ip: String?, country: String?) {
        guard let url = URL(string: "https://1.1.1.1/cdn-cgi/trace"),
              let answer = try? await session.data(for: URLRequest(url: url, timeoutInterval: 8)) else { return (nil, nil) }
        var fields: [String: String] = [:]
        for line in String(decoding: answer.0, as: UTF8.self).split(separator: "\n") {
            let kv = line.split(separator: "=", maxSplits: 1)
            if kv.count == 2 { fields[String(kv[0])] = String(kv[1]) }
        }
        return (fields["ip"], fields["loc"])
    }
}
