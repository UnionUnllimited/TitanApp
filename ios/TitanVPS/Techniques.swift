import Foundation

/// Технический режим → «Приёмы против блокировок»: what to turn on or off on one server, chosen
/// by hand to try a node that is blocked. On this device only (UserDefaults), keyed by the
/// server's name with its flag as the list shows it, so a subscription refresh keeps it. None:
/// the server's config is exactly as parseServers made it.
struct Techniques: Codable, Equatable, Sendable {
    /// TCP Fast Open and XHTTP short requests: as the subscription (and the name's markers) have it,
    /// or forced on or off.
    enum Switch: String, Codable, CaseIterable, Sendable {
        case asIs, on, off
        var title: String {
            switch self {
            case .asIs: return "Как в подписке"
            case .on: return "Вкл"
            case .off: return "Выкл"
            }
        }
    }

    /// The ClientHello cut by a freedom outbound in front of the proxy's dial (dialerProxy).
    enum Frag: String, Codable, CaseIterable, Sendable {
        case off, record, small, medium
        var title: String {
            switch self {
            case .off: return "Выкл"
            case .record: return "Разбить TLS-запись"
            case .small: return "Мелко"
            case .medium: return "Средне"
            }
        }

        /// Xray's fragment length and interval (ms). Interval 0: the TLS records the hello is cut
        /// into go out in one write (Xray v26.9.30, FragmentWriter), so only the record is split.
        var preset: (length: String, interval: String)? {
            switch self {
            case .off: return nil
            case .record: return ("100-200", "0")
            case .small: return ("10-30", "5-10")
            case .medium: return ("50-100", "10-20")
            }
        }
    }

    var tfo: Switch = .asIs
    var pktup: Switch = .asIs
    var frag: Frag = .off

    init() {}

    private enum CodingKeys: String, CodingKey { case tfo, pktup, frag }

    // A value this build doesn't know (saved by a newer one) reads as no change.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tfo = (try? c.decodeIfPresent(Switch.self, forKey: .tfo)) ?? .asIs
        pktup = (try? c.decodeIfPresent(Switch.self, forKey: .pktup)) ?? .asIs
        frag = (try? c.decodeIfPresent(Frag.self, forKey: .frag)) ?? .off
    }

    var isEmpty: Bool { self == Techniques() }

    /// For VPNController.source: a changed choice rebuilds the VPN profile.
    var signature: String { "\(tfo.rawValue),\(pktup.rawValue),\(frag.rawValue)" }

    /// One line for the tech mode's report.
    var summary: String {
        var parts: [String] = []
        if tfo != .asIs { parts.append("TFO \(tfo.title.lowercased())") }
        if pktup != .asIs { parts.append("короткие запросы XHTTP \(pktup.title.lowercased())") }
        if frag != .off { parts.append("дробление ClientHello: \(frag.title.lowercased())") }
        return parts.joined(separator: ", ")
    }

    /// Which of them a server can take: none on a plugin server (its proxy is our local
    /// sing-box / mieru gateway, or MASQUE), TFO and fragmenting on Xray's own TCP dial only,
    /// short requests on XHTTP only.
    struct Fit: Equatable {
        var tfo = false, pktup = false, frag = false
    }

    static func fit(_ server: Server) -> Fit {
        guard Plugins.endpoint(server) == nil, let json = server.xrayJson, let tag = server.proxyTag,
              let cfg = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let proxy = Plugins.outbound(cfg, tag: tag), overTCP(proxy) else { return Fit() }
        let sockopt = (proxy["streamSettings"] as? [String: Any])?["sockopt"] as? [String: Any] ?? [:]
        // A dial already chained through another outbound by the panel stays as it is.
        let chained = !((sockopt["dialerProxy"] as? String) ?? "").isEmpty
        return Fit(tfo: true, pktup: isXhttp(proxy), frag: !chained)
    }

    /// The proxy dials TCP itself: VLESS / VMess / Trojan / Shadowsocks over raw, WebSocket,
    /// gRPC, HTTPUpgrade or XHTTP on HTTP/1.1 or 2 (not mKCP, QUIC or XHTTP on HTTP/3).
    private static func overTCP(_ proxy: [String: Any]) -> Bool {
        let proto = ((proxy["protocol"] as? String) ?? "").lowercased()
        guard ["vless", "vmess", "trojan", "shadowsocks"].contains(proto) else { return false }
        let stream = proxy["streamSettings"] as? [String: Any] ?? [:]
        switch ((stream["network"] as? String) ?? "raw").lowercased() {
        case "raw", "tcp", "ws", "websocket", "grpc", "httpupgrade":
            return true
        case "xhttp", "splithttp":
            // Xray's XHTTP client takes HTTP/3 when the TLS ALPN is "h3" alone (not with REALITY).
            guard ((stream["security"] as? String) ?? "") != "reality" else { return true }
            let tls = stream["tlsSettings"] as? [String: Any] ?? [:]
            let alpn = (tls["alpn"] as? [String]) ?? ((tls["alpn"] as? String).map { $0.split(separator: ",").map(String.init) } ?? [])
            return alpn.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } != ["h3"]
        default:
            return false
        }
    }

    private static func isXhttp(_ proxy: [String: Any]) -> Bool {
        let stream = proxy["streamSettings"] as? [String: Any] ?? [:]
        return ["xhttp", "splithttp"].contains(((stream["network"] as? String) ?? "").lowercased())
    }

    /// The server's config with these on its proxy outbound (tag), for Plugins.prepare: so the
    /// connect, the list's ping, «Проверить соединение» and the VPN profile all get them. None, or
    /// a server they don't fit: the very same string.
    static func apply(_ t: Techniques, json: String, tag: String) -> String {
        guard !t.isEmpty,
              var cfg = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              var outbounds = cfg["outbounds"] as? [[String: Any]],
              let i = outbounds.firstIndex(where: { $0["tag"] as? String == tag }),
              overTCP(outbounds[i]) else { return json }
        var proxy = outbounds[i]
        // Short requests first: their own TFO goes if TFO is turned off by hand.
        switch t.pktup {
        case .on: SubscriptionAPI.packetUpOn(&proxy)
        case .off: SubscriptionAPI.packetUpOff(&proxy)
        case .asIs: break
        }
        switch t.tfo {
        case .on: SubscriptionAPI.tfoOn(&proxy)
        case .off: SubscriptionAPI.dropTfo(&proxy)
        case .asIs: break
        }
        // As parseServers after the "tfo" / "pktup" markers: XHTTP over TFO cut to fit a SYN.
        if t.tfo == .on || t.pktup == .on { SubscriptionAPI.fitXhttpInSyn(&proxy) }
        if let preset = t.frag.preset {
            var stream = proxy["streamSettings"] as? [String: Any] ?? [:]
            var sockopt = stream["sockopt"] as? [String: Any] ?? [:]
            if ((sockopt["dialerProxy"] as? String) ?? "").isEmpty {
                let fragTag = outbounds.contains(where: { $0["tag"] as? String == "frag" }) ? "titan-frag" : "frag"
                // The TCP dial is the fragment outbound's now: the proxy's socket options (TFO
                // among them, so the first piece rides the SYN) go with it.
                var own = sockopt
                own.removeValue(forKey: "dialerProxy")
                var frag: [String: Any] = [
                    "tag": fragTag,
                    "protocol": "freedom",
                    "settings": ["fragment": ["packets": "tlshello", "length": preset.length, "interval": preset.interval]],
                ]
                if !own.isEmpty { frag["streamSettings"] = ["sockopt": own] }
                sockopt["dialerProxy"] = fragTag
                stream["sockopt"] = sockopt
                proxy["streamSettings"] = stream
                // Last: the first outbound stays the default one.
                outbounds.append(frag)
            }
        }
        outbounds[i] = proxy
        cfg["outbounds"] = outbounds
        guard let data = try? JSONSerialization.data(withJSONObject: cfg, options: .sortedKeys) else { return json }
        return String(decoding: data, as: UTF8.self)
    }
}

/// The choices of every server, under one UserDefaults key (HomeView and TechModeView watch it
/// with @AppStorage: no dots in it, or SwiftUI misses the changes).
enum TechniqueStore {
    static let key = "titanTechniques"

    static func decode(_ data: Data?) -> [String: Techniques] {
        guard let data, !data.isEmpty,
              let map = try? JSONDecoder().decode([String: Techniques].self, from: data) else { return [:] }
        return map.filter { !$0.value.isEmpty }
    }

    static func encode(_ map: [String: Techniques]) -> Data {
        let kept = map.filter { !$0.value.isEmpty }
        guard !kept.isEmpty else { return Data() }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        return (try? encoder.encode(kept)) ?? Data()
    }

    static func all() -> [String: Techniques] { decode(UserDefaults.standard.data(forKey: key)) }

    static func of(_ name: String) -> Techniques { all()[name] ?? Techniques() }
}
