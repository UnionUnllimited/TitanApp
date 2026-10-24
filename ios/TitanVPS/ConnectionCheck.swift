import Foundation
import Network

/// Проверить соединение (as in Happ): requests through the running VPN itself (the app's own
/// traffic goes into the tunnel), so it shows whether the connection works right now, its
/// latency and which address the sites see. The latency is a warm one (latency()): a fresh
/// connection's first request also pays the proxy's handshake (REALITY, ML-KEM, XHTTP), DNS and
/// the route, often over a second while the VPN works fine. That is what a server's Пинг times,
/// not what a site feels once its connection is open.
enum ConnectionCheck {
    struct Result: Equatable {
        /// The latency in ms; nil when the VPN carries traffic but it couldn't be measured.
        let ms: Int?
        let ip: String?
        /// ISO code of the exit's country (Cloudflare's loc=).
        let country: String?
    }

    enum Status: Equatable {
        case idle, checking, ok(Result), failed(String)
    }

    static func run() async -> Status {
        let session = freshSession(timeout: 8)
        defer { session.finishTasksAndInvalidate() }
        async let exitInfo = exitAddress(session)
        let warm = await latency(within: 8)
        let address = await exitInfo
        if warm.answered { return .ok(Result(ms: warm.ms, ip: address.ip, country: address.country)) }
        // Google didn't answer but Cloudflare did: the VPN carries traffic (the panel's routing
        // may send Google another way), so it isn't "no answer". No number: Cloudflare's time
        // is a fresh connection's.
        if address.ip != nil { return .ok(Result(ms: nil, ip: address.ip, country: address.country)) }
        return .failed(warm.why ?? "нет ответа")
    }

    /// The Live Activity's latency (LiveActivityController, at most once a minute while the app
    /// is open): nil if it couldn't be measured within a few seconds.
    static func probe() async -> Int? {
        await latency(within: 5).ms
    }

    // MARK: The latency

    /// Google's generate_204, as a server's Пинг asks, over plain HTTP: a request on an open
    /// connection is then a few hundred bytes, with no TLS of its own (Network.framework, not
    /// URLSession, so ATS doesn't apply). Not a Russian site: the panel's routing sends those
    /// direct, past the VPN.
    private static let host = "www.gstatic.com"
    private static let request = Data("HEAD /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: keep-alive\r\n\r\n".utf8)
    /// Requests timed on the warm connection: their median is the latency.
    private static let samples = 3
    /// Each timed request's wait. One with no answer by then counts as this long (slow) and ends
    /// the measurement: its answer may still come.
    private static let sampleTimeout: TimeInterval = 1.5

    private struct Warm {
        /// The first request was answered: the VPN carries traffic.
        let answered: Bool
        let ms: Int?
        /// Why not, for Технический режим.
        let why: String?
    }

    /// The latency through the VPN: phone → VPN server → Google and back on a connection that is
    /// already open, as a site's requests go once it has loaded. One connection: its first
    /// request (the proxy's handshake, DNS, the route) has [within] seconds and isn't timed, the
    /// next ones are timed on the same connection, one after another; their median.
    private static func latency(within: TimeInterval) async -> Warm {
        let line = HTTPLine(host: host)
        defer { line.close() }
        // Cancelled (a new check, the VPN went off): ends at once, not after its waits.
        return await withTaskCancellationHandler {
            await measure(line, within: within)
        } onCancel: {
            line.close()
        }
    }

    private static func measure(_ line: HTTPLine, within: TimeInterval) async -> Warm {
        let deadline = Date().addingTimeInterval(within)
        if let why = await line.open(within: within) { return Warm(answered: false, ms: nil, why: why) }
        switch await line.ask(request, within: max(deadline.timeIntervalSinceNow, 0.5)) {
        case .ms: break
        case .timedOut: return Warm(answered: false, ms: nil, why: "таймаут")
        case .failed(let why): return Warm(answered: false, ms: nil, why: why)
        }
        var times: [Int] = []
        measuring: while times.count < samples {
            switch await line.ask(request, within: sampleTimeout) {
            case .ms(let ms): times.append(ms)
            case .timedOut:
                times.append(Int(sampleTimeout * 1000))
                break measuring
            case .failed: break measuring
            }
        }
        guard !times.isEmpty else { return Warm(answered: true, ms: nil, why: nil) }
        // The upper one of two: a slow answer isn't hidden by a quick one.
        return Warm(answered: true, ms: times.sorted()[times.count / 2], why: nil)
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

/// One TCP connection through the VPN (ConnectionCheck.latency): HTTP/1.1 requests on it, one
/// after another. What it keeps is touched on its own queue only.
private final class HTTPLine: @unchecked Sendable {
    enum Answer {
        /// The answer's head came this many ms after the request went.
        case ms(Int)
        case timedOut
        case failed(String)
    }

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "titan.latency")
    /// The answer coming in.
    private var head = Data()
    private var closed = false
    private static let end = Data("\r\n\r\n".utf8)
    private static let http = Data("HTTP/1.".utf8)

    init(host: String) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        connection = NWConnection(host: NWEndpoint.Host(host), port: .http, using: NWParameters(tls: nil, tcp: tcp))
    }

    /// Connected within [timeout]: nil, else why not.
    func open(within timeout: TimeInterval) async -> String? {
        await withCheckedContinuation { continuation in
            let once = Once(continuation)
            queue.async { [self] in
                guard !closed else { return once.finish("отменено") }
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready: once.finish(nil)
                    case .waiting(let error), .failed(let error): once.finish(Self.describe(error))
                    case .cancelled: once.finish("отменено")
                    default: break
                    }
                }
                connection.start(queue: queue)
                queue.asyncAfter(deadline: .now() + timeout) { once.finish("таймаут") }
            }
        }
    }

    /// [request] and its answer's head (a HEAD's answer has nothing after it). After anything
    /// but .ms the connection is no use: an answer may still be on its way.
    func ask(_ request: Data, within timeout: TimeInterval) async -> Answer {
        await withCheckedContinuation { continuation in
            let once = Once(continuation)
            queue.async { [self] in
                guard !closed else { return once.finish(.failed("отменено")) }
                head = Data()
                let start = DispatchTime.now().uptimeNanoseconds
                connection.send(content: request, completion: .contentProcessed { error in
                    if let error { once.finish(.failed(Self.describe(error))) }
                })
                read(start, once)
                queue.asyncAfter(deadline: .now() + timeout) { once.finish(.timedOut) }
            }
        }
    }

    /// A wait still under way ends with it.
    func close() {
        queue.async { [self] in
            closed = true
            connection.cancel()
        }
    }

    private func read(_ start: UInt64, _ once: Once<Answer>) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [self] data, _, complete, error in
            if let data { head.append(data) }
            if head.range(of: Self.end) != nil {
                let ms = Int((DispatchTime.now().uptimeNanoseconds - start) / 1_000_000)
                once.finish(head.starts(with: Self.http) ? .ms(ms) : .failed("ответ не HTTP"))
            } else if let error {
                once.finish(.failed(Self.describe(error)))
            } else if complete {
                once.finish(.failed("соединение закрыто"))
            } else {
                read(start, once)
            }
        }
    }

    private static func describe(_ error: NWError) -> String {
        switch error {
        case .posix(let code):
            switch code {
            case .ETIMEDOUT: return "таймаут"
            case .ECONNREFUSED: return "не удалось подключиться"
            case .ECONNRESET, .EPIPE: return "соединение оборвалось"
            case .ENETDOWN, .ENETUNREACH, .EHOSTUNREACH: return "нет сети"
            default: return "ошибка сети (\(code.rawValue))"
            }
        case .dns: return "DNS: адрес не найден"
        default: return error.localizedDescription
        }
    }
}

/// A continuation resumed once: by the answer or by its timeout, whichever comes first.
private final class Once<T>: @unchecked Sendable {
    private var continuation: CheckedContinuation<T, Never>?
    private let lock = NSLock()

    init(_ continuation: CheckedContinuation<T, Never>) { self.continuation = continuation }

    func finish(_ value: T) {
        lock.lock()
        let c = continuation
        continuation = nil
        lock.unlock()
        c?.resume(returning: value)
    }
}
