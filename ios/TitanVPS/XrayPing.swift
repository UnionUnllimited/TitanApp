import Foundation
import LibXray

/// Real ping through Xray (libXray's pingBatch), as on Android: a request through the
/// server itself (TCP, TLS and the proxy handshake included), not just a TCP connect.
/// Servers that route through a balancer are measured on its members; their best wins.
enum XrayPing {
    static let url = "https://www.gstatic.com/generate_204"
    private static let services: Set<String> = ["freedom", "blackhole", "dns", "loopback"]
    /// Balancer members measured per server.
    private static let maxMembers = 30

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

    /// Probes per pingBatch call (libXray is patched to take that many; one Xray instance
    /// runs them all in parallel). Calls themselves run one at a time inside libXray.
    private static let batchSize = 80

    /// Measures [servers], reporting each one's best ms (-1: nothing answered) as soon as
    /// its call is done. Every server's members go into the same call where they fit.
    static func pingMany(_ servers: [Server], timeout: Int = 5, onResult: @escaping @MainActor (String, Int, String?) -> Void) async {
        struct Item { let server: String; let json: String; let tag: String }
        var batches: [[Item]] = [[]]
        // Our protocol clients (sing-box, mieru) run here for the time of the ping.
        var clients: [Int] = []
        defer { clients.forEach(Titan.stop) }
        for server in servers {
            let prepared: PreparedServer
            do {
                prepared = try Plugins.prepare(server)
                if let plugin = prepared.plugin { clients.append(try Titan.start(plugin)) }
            } catch {
                await onResult(server.id, -1, error.localizedDescription)
                continue
            }
            let json = prepared.json
            guard let tag = server.proxyTag,
                  let cfg = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else {
                await onResult(server.id, -1, "Не удалось разобрать конфиг сервера")
                continue
            }
            let items = tags(cfg, proxyTag: tag).map { Item(server: server.id, json: json, tag: $0) }
            if !batches[batches.count - 1].isEmpty && batches[batches.count - 1].count + items.count > batchSize { batches.append([]) }
            batches[batches.count - 1].append(contentsOf: items)
        }
        for batch in batches where !batch.isEmpty {
            if Task.isCancelled { return }
            let delays = await run(batch.map { ($0.json, $0.tag) }, timeout: timeout)
            var best: [String: Int] = [:]
            var reason: [String: String] = [:]
            var order: [String] = []
            for (item, result) in zip(batch, delays) {
                let ms = result.0
                if best[item.server] == nil { order.append(item.server); best[item.server] = -1 }
                if ms >= 0 && (best[item.server]! < 0 || ms < best[item.server]!) { best[item.server] = ms }
                if ms < 0, reason[item.server] == nil, let e = result.1, !e.isEmpty { reason[item.server] = shortReason(e) }
            }
            if Task.isCancelled { return }
            for id in order {
                let ms = best[id] ?? -1
                await onResult(id, ms, ms < 0 ? reason[id] : nil)
            }
        }
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
