import Foundation

/// The config Xray runs in the tunnel, built from a server's config the way the Android app
/// does it: our TUN inbound (its file descriptor is added by the tunnel extension), every DNS
/// query of the phone into Xray's DNS, everything else (outbounds, routing, DNS servers) from
/// the panel. No geo files on iOS (the tunnel has ~50 MB of memory): geoip/geosite entries
/// are replaced or dropped.
enum XrayRunConfig {
    static let tunTag = "tun-in"
    static let dnsOutTag = "dns-out"
    static let sniffingKey = "_titanSniffing"
    /// The DNS outbounds' policy level (or the next one the panel's config doesn't use).
    static let dnsLevel = 9
    private static let blockTag = "titan-block"
    private static let youtube = ["youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com",
                                  "youtube.googleapis.com", "ggpht.com", "youtube-nocookie.com"]
    private static let privateNets = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
                                      "100.64.0.0/10", "::1/128", "fc00::/7", "fe80::/10"]

    static let mtu = 1500

    static func build(serverJson: String) throws -> String {
        guard var cfg = (try? JSONSerialization.jsonObject(with: Data(serverJson.utf8))) as? [String: Any] else {
            throw SubscriptionError(message: "Не удалось прочитать настройки сервера")
        }
        // No access or DNS log: a line per connection or query that nobody reads in the tunnel.
        cfg["log"] = ["loglevel": "warning", "access": "none", "dnsLog": false]
        // No API, stats or metrics: the app reads the speed from utun's counters. Only the rules
        // sending traffic to their tags go with them (their inbounds went in parseServers).
        var serviceTags = Set<String>()
        for key in ["api", "metrics"] {
            if let tag = (cfg.removeValue(forKey: key) as? [String: Any])?["tag"] as? String { serviceTags.insert(tag) }
        }
        cfg.removeValue(forKey: "stats")

        // The panel's sniffing if it had one; by default the destination becomes the sniffed
        // domain, so "direct" re-resolves it (IPv4) instead of dialing an unreachable IPv6.
        var sniffing = cfg.removeValue(forKey: sniffingKey) as? [String: Any]
            ?? ["destOverride": ["http", "tls", "quic"], "routeOnly": false]
        sniffing["enabled"] = true
        cfg["inbounds"] = [[
            "tag": tunTag,
            "protocol": "tun",
            "settings": ["name": "utun", "mtu": mtu],
            "sniffing": sniffing,
        ]]

        var dns = cfg["dns"] as? [String: Any] ?? ["servers": ["1.1.1.1", "8.8.8.8"]]
        dns["queryStrategy"] = "UseIPv4"
        cfg["dns"] = dns

        var outbounds = cfg["outbounds"] as? [[String: Any]] ?? []
        serviceTags.subtract(outbounds.compactMap { $0["tag"] as? String })
        if !outbounds.contains(where: { $0["tag"] as? String == dnsOutTag }) {
            outbounds.append(["tag": dnsOutTag, "protocol": "dns"])
        }
        // Each DNS query of the phone is its own UDP flow in the TUN, and the DNS outbound keeps
        // it for connIdle (300 s by default): the tunnel's main use of memory. On a level of its
        // own a flow ends 10 s after its last query.
        let level = freeLevel(cfg)
        for i in outbounds.indices where outbounds[i]["tag"] as? String == dnsOutTag || outbounds[i]["protocol"] as? String == "dns" {
            var settings = outbounds[i]["settings"] as? [String: Any] ?? [:]
            settings["userLevel"] = level
            outbounds[i]["settings"] = settings
        }
        var policy = cfg["policy"] as? [String: Any] ?? [:]
        var levels = policy["levels"] as? [String: Any] ?? [:]
        levels[String(level)] = ["connIdle": 10, "handshake": 4, "uplinkOnly": 1, "downlinkOnly": 1]
        policy["levels"] = levels
        cfg["policy"] = policy
        var block = blockTag
        if let existing = outbounds.first(where: { $0["protocol"] as? String == "blackhole" })?["tag"] as? String {
            block = existing
        } else {
            outbounds.append(["tag": blockTag, "protocol": "blackhole"])
        }
        cfg["outbounds"] = outbounds

        var routing = cfg["routing"] as? [String: Any] ?? [:]
        var rules: [[String: Any]] = [
            // Every DNS query of the phone goes to Xray's DNS module first.
            ["type": "field", "inboundTag": [tunTag], "port": "53", "outboundTag": dnsOutTag],
            // YouTube over QUIC breaks playback through proxies; blocked, the app falls back to TCP.
            ["type": "field", "network": "udp", "port": "443", "domain": youtube.map { "domain:\($0)" }, "outboundTag": block],
        ]
        rules += (routing["rules"] as? [[String: Any]] ?? [])
            .filter { !serviceTags.contains($0["outboundTag"] as? String ?? "") }
            .compactMap(withoutGeo)
        routing["rules"] = rules
        cfg["routing"] = routing

        let data = try JSONSerialization.data(withJSONObject: cfg)
        return String(decoding: data, as: UTF8.self)
    }

    /// Rules bound to the panel's own inbound tags must follow our TUN inbound.
    static func retargetInboundRules(_ cfg: inout [String: Any], oldTags: Set<String>) {
        guard !oldTags.isEmpty, var routing = cfg["routing"] as? [String: Any],
              var rules = routing["rules"] as? [[String: Any]] else { return }
        for i in rules.indices {
            guard let tags = rules[i]["inboundTag"] as? [String], tags.contains(where: oldTags.contains) else { continue }
            var mapped = tags.filter { !oldTags.contains($0) }
            if !mapped.contains(tunTag) { mapped.append(tunTag) }
            rules[i]["inboundTag"] = mapped
        }
        routing["rules"] = rules
        cfg["routing"] = routing
    }

    /// dnsLevel, or the next level after it that no policy, outbound or user of the panel's has.
    private static func freeLevel(_ cfg: [String: Any]) -> Int {
        let policy = cfg["policy"] as? [String: Any]
        var used = Set(((policy?["levels"] as? [String: Any]) ?? [:]).keys.compactMap { Int($0) })
        func scan(_ value: Any) {
            if let object = value as? [String: Any] {
                for (key, v) in object {
                    if key == "level" || key == "userLevel", let n = v as? Int { used.insert(n) } else { scan(v) }
                }
            } else if let array = value as? [Any] {
                for item in array { scan(item) }
            }
        }
        scan(cfg["outbounds"] ?? [])
        var level = dnsLevel
        while used.contains(level) { level += 1 }
        return level
    }

    /// A rule without geoip/geosite lists (no geo files on iOS): geoip:private becomes the
    /// private networks themselves; a rule left with no condition it had is dropped.
    private static func withoutGeo(_ rule: [String: Any]) -> [String: Any]? {
        var r = rule
        if let ips = r["ip"] as? [String] {
            var out: [String] = []
            for ip in ips {
                if ip == "geoip:private" { out += privateNets } else if !ip.lowercased().hasPrefix("geoip:") && !ip.hasPrefix("ext:") { out.append(ip) }
            }
            if out.isEmpty { return nil }
            r["ip"] = out
        }
        if let domains = r["domain"] as? [String] {
            let out = domains.filter { !$0.lowercased().hasPrefix("geosite:") && !$0.hasPrefix("ext:") }
            if out.isEmpty { return nil }
            r["domain"] = out
        }
        return r
    }
}
