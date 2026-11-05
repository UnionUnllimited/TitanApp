import Foundation
import LibXray

/// Real ping through Xray (libXray's pingBatch), as on Android: a request through the
/// server itself (TCP, TLS and the proxy handshake included), not just a TCP connect.
/// Servers that route through a balancer are measured on its members; their best wins.
enum XrayPing {
    static let url = "https://www.gstatic.com/generate_204"
    private static let services: Set<String> = ["freedom", "blackhole", "dns", "loopback"]
    /// Balancer members measured per server (each its own call; the first that answers counts).
    private static let maxMembers = 30

    /// Outbound tags worth measuring: the proxy, or the members of the catch-all balancer (АВТО,
    /// UnlimitedServer). Its rule may name a network ("tcp,udp" is all traffic): not a condition here.
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

    /// Calls start one after another this far apart (libXray is patched so its calls run side
    /// by side, each with its own Xray instance). The whole list in one burst made a busy
    /// network drop handshakes: live servers read as timeouts. A call that only waits out a
    /// dead server's timeout adds no load, so it doesn't hold the others back.
    private static let startSpacing: UInt64 = 100_000_000
    /// Calls running at once at most (each is an Xray instance in memory).
    private static let maxCalls = 24
    /// A balancer's members running at once: АВТО doesn't take every slot of the list.
    private static let perServer = 6
    /// A balancer whose members all failed is tried again on this many of them only.
    private static let retryPerServer = 6
    /// The retry: few at a time, as a ping by hand (alone, it answers when it's alive).
    private static let retryCalls = 2
    /// What failed gets a second, slower try after the rest (as on Android).
    private static let retryTimeout = 6
    /// A server that answered is asked once more in the same call, on the same Xray instance
    /// (pingBatch's "warmTimeout", the xray-tfo patch of libXray), as Android's second attempt
    /// on its warm core: a new request over new connections wherever Xray opens them (each with
    /// its TCP and TFO dial), with the outbound's own state warm, as in a running tunnel (a
    /// VLESS-encryption 0-RTT ticket, XMUX). The better one counts. (A second call of its own
    /// started a new instance: only the kernel's TFO cookie and DNS were warm then.)
    private static let warmTimeout = 3
    /// A list's answer slower than this is measured once more after the list, in the retry
    /// ([retryCalls] at a time, [retryTimeout], one attempt: no warm second), and the better one
    /// is shown, as Android's recheck (RECHECK_ABOVE_MS): the first is often just the crowd. The
    /// first answer is shown at once all the same, its spinner on until the recheck: Стоп or
    /// leaving the app keeps it. One server asked for (its own ping button) runs alone already:
    /// as there, no recheck.
    private static let recheckAbove = 1500
    /// At most this many of a list's slow answers are measured again (Android has no such limit:
    /// its VPN stays on): the first in the list's order (the tab on screen comes first). The rest
    /// keep their first answer. The VPN is off for the recheck: it adds about [recheckMax] /
    /// [retryCalls] x [retryTimeout] = 18 s at most (twice that for slow servers behind one entry
    /// host, measured one at a time).
    private static let recheckMax = 6

    private struct Item: Sendable { let json: String; let tag: String }

    /// One probe: a server's own proxy, or one member of its balancer, and the host it dials.
    private struct Unit: Sendable {
        let server: Int
        let item: Item
        let host: String
    }

    private enum Event: Sendable {
        case tick
        case done(unit: Int, ms: Int, why: String?)
    }

    /// Measures [servers] in the order given, reporting each one's ms (-1: nothing answered)
    /// as soon as it's known, as Happ does. A balancer (АВТО) is measured on its members and
    /// shown with the first that answers. What fails is tried again, slower, after the rest;
    /// in a list, so is the member that answered slower than [recheckAbove] (the first
    /// [recheckMax] such servers). onResult's last value says whether the result is final: such a
    /// slow answer is reported at once as not final, then once more, final, with the better of
    /// the two. Every other result is final.
    static func pingMany(_ servers: [Server], timeout: Int = 5, onResult: @escaping @MainActor (String, Int, String?, Bool) -> Void) async {
        // Our protocol clients (sing-box, mieru) run here for the time of the ping.
        var clients: [Int] = []
        defer { clients.forEach(Titan.stop) }
        var prepared: [Int: PreparedServer] = [:]
        var units: [Unit] = []
        for (index, server) in servers.enumerated() {
            if Task.isCancelled { return }
            let p: PreparedServer
            do {
                p = try Plugins.prepare(server)
                if let plugin = p.plugin { clients.append(try Titan.start(plugin)) }
            } catch {
                await onResult(server.id, -1, error.localizedDescription, true)
                continue
            }
            guard let tag = server.proxyTag,
                  let cfg = (try? JSONSerialization.jsonObject(with: Data(p.json.utf8))) as? [String: Any] else {
                await onResult(server.id, -1, "Не удалось разобрать конфиг сервера", true)
                continue
            }
            prepared[index] = p
            units += tags(cfg, proxyTag: tag).map {
                Unit(server: index, item: Item(json: p.json, tag: $0), host: host(cfg, tag: $0) ?? "server \(index) \($0)")
            }
        }

        var failed: [Int: String] = [:]
        // A list's slow answers, to be measured again: the unit that gave it and its ms.
        let recheck = servers.count > 1
        var slow: [Int: (unit: Int, ms: Int)] = [:]
        await measure(units, timeout: timeout, maxCalls: maxCalls) { server, unit, ms, why in
            if recheck, ms > recheckAbove {
                // Shown now (kept if the run stops), final after its recheck.
                slow[server] = (unit: unit, ms: ms)
                await onResult(servers[server].id, ms, nil, false)
            } else if ms >= 0 {
                await onResult(servers[server].id, ms, nil, true)
            } else {
                failed[server] = why ?? ""
            }
        }
        if Task.isCancelled { return }
        // Beyond [recheckMax] (in the list's order) the first answer stands.
        for server in slow.keys.sorted().dropFirst(recheckMax) {
            if let s = slow.removeValue(forKey: server) { await onResult(servers[server].id, s.ms, nil, true) }
        }
        if failed.isEmpty && slow.isEmpty { return }
        // In the list's order: a slow server's member that answered, a failed one's members.
        var retried: [Int: Int] = [:]
        let again = units.indices.filter { i in
            let u = units[i]
            if let s = slow[u.server] { return s.unit == i }
            guard failed[u.server] != nil, retried[u.server, default: 0] < retryPerServer else { return false }
            retried[u.server, default: 0] += 1
            return true
        }.map { units[$0] }
        // A recheck is one attempt (no warm second); a failed server's retry keeps its second.
        await measure(again, timeout: retryTimeout, maxCalls: retryCalls, coldOnly: Set(slow.keys)) { server, _, ms, why in
            // A slow answer measured again: the better of the two (no answer now: the first).
            if let first = slow[server] {
                await onResult(servers[server].id, ms >= 0 ? min(first.ms, ms) : first.ms, nil, true)
                return
            }
            // A failed protocol client says why in its own log.
            let clientLog = ms < 0 && prepared[server]?.log != nil ? prepared[server]?.logTail() : nil
            let reason = why ?? failed[server].flatMap { $0.isEmpty ? nil : $0 }
            let text = [reason, clientLog.map { "[\($0)]" }].compactMap { $0 }.joined(separator: " ")
            await onResult(servers[server].id, ms, ms < 0 && !text.isEmpty ? text : nil, true)
        }
    }

    /// Where an outbound dials first (its server's address, lower case); nil when not found.
    static func host(_ cfg: [String: Any], tag: String) -> String? {
        guard let ob = (cfg["outbounds"] as? [[String: Any]])?.first(where: { $0["tag"] as? String == tag }) else { return nil }
        let s = ob["settings"] as? [String: Any] ?? [:]
        let first = (s["vnext"] as? [[String: Any]])?.first ?? (s["servers"] as? [[String: Any]])?.first ?? s
        guard let address = first["address"] as? String, !address.isEmpty else { return nil }
        return address.lowercased()
    }

    /// Starts [units] in order, one every [startSpacing], at most [maxCalls] at once and
    /// [perServer] of one server. One host gets one probe at a time until it's found dead:
    /// servers behind one entry (a bridge) all timed out pinged together, yet answered one by
    /// one. [each] gets a server's result once (server, unit, ms, reason): its first member
    /// that answers (that unit), or its first failure reason when none did (the last unit).
    /// Members of a server already shown aren't started. Stopped: nothing new starts. The
    /// servers in [coldOnly] get no warm second probe.
    private static func measure(_ units: [Unit], timeout: Int, maxCalls: Int, coldOnly: Set<Int> = [],
                                each: (Int, Int, Int, String?) async -> Void) async {
        var left: [Int: Int] = [:]
        for u in units { left[u.server, default: 0] += 1 }
        var shown = Set<Int>()
        var reason: [Int: String] = [:]
        await withTaskGroup(of: Event.self) { group in
            var pending = Array(units.indices)
            var busy: [Int: Int] = [:]
            var hostBusy: [String: Int] = [:]
            var hostAlive = Set<String>()
            var hostFails: [String: Int] = [:]
            var hostDead = Set<String>()
            var running = 0
            var ticking = false
            while true {
                if !ticking, running < maxCalls, !Task.isCancelled,
                   let at = pending.firstIndex(where: { i in
                       busy[units[i].server, default: 0] < perServer
                           && (hostDead.contains(units[i].host) || hostBusy[units[i].host, default: 0] == 0)
                   }) {
                    let index = pending.remove(at: at)
                    let item = units[index].item
                    let warm = coldOnly.contains(units[index].server) ? 0 : warmTimeout
                    busy[units[index].server, default: 0] += 1
                    hostBusy[units[index].host, default: 0] += 1
                    running += 1
                    ticking = true
                    group.addTask {
                        let (ms, why) = await probe(item, timeout: timeout, warmTimeout: warm)
                        return .done(unit: index, ms: ms, why: why)
                    }
                    group.addTask {
                        try? await Task.sleep(nanoseconds: startSpacing)
                        return .tick
                    }
                }
                guard let event = await group.next() else { break }
                switch event {
                case .tick:
                    ticking = false
                case let .done(unit, ms, why):
                    running -= 1
                    let server = units[unit].server
                    let host = units[unit].host
                    busy[server, default: 1] -= 1
                    hostBusy[host, default: 1] -= 1
                    left[server, default: 1] -= 1
                    // A host that failed twice and never answered is likely down: its other probes
                    // needn't wait in line (one dead server behind a live entry isn't enough).
                    if ms >= 0 {
                        hostAlive.insert(host)
                        hostDead.remove(host)
                    } else if !hostAlive.contains(host) {
                        hostFails[host, default: 0] += 1
                        if hostFails[host, default: 0] >= 2 { hostDead.insert(host) }
                    }
                    if shown.contains(server) || Task.isCancelled { continue }
                    if ms >= 0 {
                        shown.insert(server)
                        // Its other members aren't needed any more.
                        pending.removeAll { units[$0].server == server }
                        await each(server, unit, ms, nil)
                    } else {
                        if reason[server] == nil, let why, !why.isEmpty { reason[server] = why }
                        if left[server, default: 0] <= 0 {
                            shown.insert(server)
                            await each(server, unit, -1, reason[server])
                        }
                    }
                }
            }
        }
    }

    /// One call with one probe and, if it answered (and [warmTimeout] isn't 0), its warm second
    /// on the same instance: the better ms (-1: no answer) and the reason of a failure.
    private static func probe(_ item: Item, timeout: Int, warmTimeout: Int) async -> (Int, String?) {
        let result = await run([(item.json, item.tag)], timeout: timeout, warmTimeout: warmTimeout).first ?? (-1, nil)
        return (result.0, result.0 >= 0 ? nil : result.1.map(shortReason))
    }

    /// Go's `Head "<url>": <reason>` without the URL part (the reason is what matters).
    static func shortReason(_ error: String) -> String {
        guard error.hasPrefix("Head \""), let r = error.range(of: "\": ") else { return error }
        return String(error[r.upperBound...])
    }

    /// One pingBatch call: ms per (config, outbound tag), -1 for a failure (with Xray's reason).
    /// With [warmTimeout] (seconds, 0: none) one that answered is probed once more on the same
    /// instance: "delay" is the better of the two ("delays" has both).
    private static func run(_ items: [(String, String)], timeout: Int, warmTimeout: Int) async -> [(Int, String?)] {
        await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                var failed = [(Int, String?)](repeating: (-1, nil), count: items.count)
                let request: [String: Any] = [
                    "apiVersion": 3, "method": "pingBatch",
                    "payload": ["configs": items.map { ["xrayJson": $0.0, "outboundTag": $0.1] }, "timeout": timeout,
                                "warmTimeout": warmTimeout, "url": url],
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
