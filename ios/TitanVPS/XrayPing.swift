import Foundation
import LibXray

/// Real ping through Xray (libXray's pingBatch), as on Android: a request through the
/// server itself (TCP, TLS and the proxy handshake included), not just a TCP connect.
/// Servers that route through a balancer are measured on its members; their best wins.
enum XrayPing {
    static let url = "https://www.gstatic.com/generate_204"
    private static let services: Set<String> = ["freedom", "blackhole", "dns", "loopback"]
    /// Balancer members measured per server (in one call, so they count toward [inFlight]).
    private static let maxMembers = 12

    /// Outbound tags worth measuring: the proxy, or the members of the catch-all balancer.
    static func tags(_ cfg: [String: Any], proxyTag: String) -> [String] {
        guard let routing = cfg["routing"] as? [String: Any],
              let rules = routing["rules"] as? [[String: Any]],
              let balancerTag = rules.last(where: { r in
                  !((r["balancerTag"] as? String) ?? "").isEmpty && r["domain"] == nil && r["ip"] == nil && r["port"] == nil && r["protocol"] == nil
              })?["balancerTag"] as? String,
              let balancer = (routing["balancers"] as? [[String: Any]])?.first(where: { $0["tag"] as? String == balancerTag }),
              let selectors = balancer["selector"] as? [String] else { return [proxyTag] }
        let members = (cfg["outbounds"] as? [[String: Any]] ?? [])
            .filter { !services.contains(($0["protocol"] as? String) ?? "") }
            .compactMap { $0["tag"] as? String }
            .filter { tag in !tag.isEmpty && selectors.contains { tag.hasPrefix($0) } }
        return members.isEmpty ? [proxyTag] : Array(members.prefix(maxMembers))
    }

    /// Probes in flight at once, over all calls (libXray is patched so its calls run side by
    /// side, each with its own Xray instance). The whole list in one burst made a busy network
    /// drop handshakes: live servers read as timeouts and their result came only with the slowest.
    private static let inFlight = 8
    /// What failed gets a second, slower try (as on Android), fewer at a time.
    private static let retryInFlight = 4
    private static let retryTimeout = 8

    private struct Item { let server: String; let json: String; let tag: String }

    /// One server: the probes of its call (its members, for a balancer) and its client's log.
    private struct Work {
        let id: String
        let items: [Item]
        let prepared: PreparedServer
    }

    private struct Outcome {
        let index: Int
        let weight: Int
        let ms: Int
        let why: String?
    }

    /// Measures [servers] a few at a time in the order given, reporting each one's best ms
    /// (-1: nothing answered) as soon as it's known, as Happ does. One that fails is tried
    /// again, slower, after the rest; its result comes then.
    static func pingMany(_ servers: [Server], timeout: Int = 5, onResult: @escaping @MainActor (String, Int, String?) -> Void) async {
        // Our protocol clients (sing-box, mieru) run here for the time of the ping.
        var clients: [Int] = []
        defer { clients.forEach(Titan.stop) }
        var work: [Work] = []
        for server in servers {
            let prepared: PreparedServer
            do {
                prepared = try Plugins.prepare(server)
                if let plugin = prepared.plugin { clients.append(try Titan.start(plugin)) }
            } catch {
                await onResult(server.id, -1, error.localizedDescription)
                continue
            }
            guard let tag = server.proxyTag,
                  let cfg = (try? JSONSerialization.jsonObject(with: Data(prepared.json.utf8))) as? [String: Any] else {
                await onResult(server.id, -1, "Не удалось разобрать конфиг сервера")
                continue
            }
            let items = tags(cfg, proxyTag: tag).map { Item(server: server.id, json: prepared.json, tag: $0) }
            work.append(Work(id: server.id, items: items, prepared: prepared))
        }
        var failed: [Work] = []
        await measureAll(work, timeout: timeout, inFlight: inFlight) { w, ms, _ in
            if ms >= 0 { await onResult(w.id, ms, nil) } else { failed.append(w) }
        }
        if Task.isCancelled || failed.isEmpty { return }
        await measureAll(failed, timeout: retryTimeout, inFlight: retryInFlight) { w, ms, why in
            // A failed protocol client says why in its own log.
            let clientLog = ms < 0 && w.prepared.log != nil ? w.prepared.logTail() : nil
            let text = [why, clientLog.map { "[\($0)]" }].compactMap { $0 }.joined(separator: " ")
            await onResult(w.id, ms, ms < 0 && !text.isEmpty ? text : nil)
        }
    }

    /// One call per server, started in order while their probes fit in [inFlight]; [each]
    /// gets a server's outcome as soon as its call is done. Stopped: nothing new starts.
    private static func measureAll(_ work: [Work], timeout: Int, inFlight: Int,
                                   each: (Work, Int, String?) async -> Void) async {
        await withTaskGroup(of: Outcome.self) { group in
            var free = inFlight
            var next = 0
            while true {
                while next < work.count, !Task.isCancelled {
                    let weight = min(max(work[next].items.count, 1), inFlight)
                    guard weight <= free else { break }
                    free -= weight
                    let index = next
                    let items = work[next].items
                    group.addTask {
                        let (ms, why) = await best(items, timeout: timeout)
                        return Outcome(index: index, weight: weight, ms: ms, why: why)
                    }
                    next += 1
                }
                guard let outcome = await group.next() else { break }
                free += outcome.weight
                if !Task.isCancelled { await each(work[outcome.index], outcome.ms, outcome.why) }
            }
        }
    }

    /// One call: the server's best ms (-1: nothing answered) with the reason of a failure.
    private static func best(_ items: [Item], timeout: Int) async -> (Int, String?) {
        let results = await run(items.map { ($0.json, $0.tag) }, timeout: timeout)
        var top = -1
        var reason: String?
        for (ms, error) in results {
            if ms >= 0 && (top < 0 || ms < top) { top = ms }
            if ms < 0, reason == nil, let e = error, !e.isEmpty { reason = shortReason(e) }
        }
        return (top, top >= 0 ? nil : reason)
    }

    /// Go's `Head "<url>": <reason>` without the URL part (the reason is what matters).
    static func shortReason(_ error: String) -> String {
        guard error.hasPrefix("Head \""), let r = error.range(of: "\": ") else { return error }
        return String(error[r.upperBound...])
    }

    /// One pingBatch call: ms per (config, outbound tag), -1 for a failure (with Xray's reason).
    private static func run(_ items: [(String, String)], timeout: Int) async -> [(Int, String?)] {
        await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                var failed = [(Int, String?)](repeating: (-1, nil), count: items.count)
                let request: [String: Any] = [
                    "apiVersion": 3, "method": "pingBatch",
                    "payload": ["configs": items.map { ["xrayJson": $0.0, "outboundTag": $0.1] }, "timeout": timeout, "url": url],
                ]
                guard let data = try? JSONSerialization.data(withJSONObject: request) else { cont.resume(returning: failed); return }
                let response = LibXrayInvoke(String(decoding: data, as: UTF8.self))
                let parsed = (try? JSONSerialization.jsonObject(with: Data(response.utf8))) as? [String: Any]
                guard let obj = parsed, obj["success"] as? Bool == true,
                      let results = (obj["data"] as? [String: Any])?["results"] as? [[String: Any]],
                      results.count == items.count else {
                    let e = (parsed?["error"] as? String) ?? "libXray не ответил"
                    failed = failed.map { _ in (-1, e) }
                    cont.resume(returning: failed)
                    return
                }
                cont.resume(returning: results.map { r in
                    r["success"] as? Bool == true ? (max((r["delay"] as? Int) ?? -1, -1), nil) : (-1, r["error"] as? String)
                })
            }
        }
    }
}
