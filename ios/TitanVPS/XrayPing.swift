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

    /// ms, or -1 if nothing answered.
    static func ping(_ server: Server, timeout: Int = 5) async -> Int {
        guard let tag = server.proxyTag, let json = try? Plugins.prepare(server),
              let cfg = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return -1 }
        let members = tags(cfg, proxyTag: tag)
        return await withTaskGroup(of: Int.self) { group in
            // A few members at a time: many at once crowd each other out on a weak network.
            var next = 0
            var best = -1
            while next < min(4, members.count) {
                let member = members[next]
                next += 1
                group.addTask { await pingOne(json, tag: member, timeout: timeout) }
            }
            while let ms = await group.next() {
                if ms >= 0 && (best < 0 || ms < best) { best = ms }
                if next < members.count {
                    let member = members[next]
                    next += 1
                    group.addTask { await pingOne(json, tag: member, timeout: timeout) }
                }
            }
            return best
        }
    }

    private static func pingOne(_ json: String, tag: String, timeout: Int) async -> Int {
        await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                let request: [String: Any] = [
                    "apiVersion": 3, "method": "pingBatch",
                    "payload": ["configs": [["xrayJson": json, "outboundTag": tag]], "timeout": timeout, "url": url],
                ]
                guard let data = try? JSONSerialization.data(withJSONObject: request) else { cont.resume(returning: -1); return }
                let response = LibXrayInvoke(String(decoding: data, as: UTF8.self))
                guard let obj = (try? JSONSerialization.jsonObject(with: Data(response.utf8))) as? [String: Any],
                      obj["success"] as? Bool == true,
                      let result = ((obj["data"] as? [String: Any])?["results"] as? [[String: Any]])?.first,
                      result["success"] as? Bool == true else { cont.resume(returning: -1); return }
                cont.resume(returning: max((result["delay"] as? Int) ?? -1, -1))
            }
        }
    }
}
