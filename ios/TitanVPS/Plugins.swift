import CryptoKit
import Foundation

/// Our protocols beyond Xray's own, as on Android (core/Plugins.kt): which one a host is
/// (Remnawave Host Mapper's "titan" field, else its name) and its credentials, derived from
/// the user's Shadowsocks password like the node sync does. On iOS for now only MASQUE runs
/// (Xray's own client); the others need their clients in the tunnel.
enum PluginKind: String {
    case naive, mieru, tuic, anytls, shadowtls, ssh, masque

    var title: String {
        switch self {
        case .naive: return "Naive"
        case .mieru: return "Mieru"
        case .tuic: return "TUIC"
        case .anytls: return "AnyTLS"
        case .shadowtls: return "ShadowTLS"
        case .ssh: return "SSH"
        case .masque: return "MASQUE"
        }
    }

    /// Xray speaks it itself.
    var supportedOnIOS: Bool { self == .masque }

    static func of(_ text: String) -> PluginKind? {
        let n = text.lowercased()
        if n.contains("naive") { return .naive }
        if n.contains("mieru") { return .mieru }
        if n.contains("masque") { return .masque }
        if n.contains("tuic") { return .tuic }
        if n.contains("anytls") { return .anytls }
        if n.contains("shadowtls") { return .shadowtls }
        if n.contains("ssh") { return .ssh }
        return nil
    }
}

struct PluginEndpoint {
    let kind: PluginKind
    let host: String
    let port: Int
    let secret: String
    /// MASQUE over HTTP/2 (TCP) instead of HTTP/3.
    let http2: Bool

    private var derived: String {
        SHA256.hash(data: Data("titan:\(secret)".utf8)).map { String(format: "%02x", $0) }.joined()
    }
    var user: String { String(derived.prefix(16)) }
    var password: String { String(derived.dropFirst(16).prefix(32)) }
}

enum Plugins {
    static func outbound(_ cfg: [String: Any], tag: String) -> [String: Any]? {
        (cfg["outbounds"] as? [[String: Any]])?.first { $0["tag"] as? String == tag }
    }

    /// The plugin endpoint of a server, or nil for a regular Xray server.
    static func endpoint(_ server: Server) -> PluginEndpoint? {
        guard let json = server.xrayJson, let tag = server.proxyTag,
              let cfg = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              let ob = outbound(cfg, tag: tag) else { return nil }
        let mapped = ((ob["titan"] as? String) ?? "").lowercased().replacingOccurrences(of: "titan-", with: "")
        let source = mapped.isEmpty ? server.name.lowercased() : mapped
        guard let kind = PluginKind.of(source) else { return nil }
        let http2 = kind == .masque && (source.contains("tcp") || source.contains("h2"))
        let settings = ob["settings"] as? [String: Any] ?? [:]
        if let s = (settings["servers"] as? [[String: Any]])?.first,
           let address = s["address"] as? String, let secret = s["password"] as? String, !address.isEmpty, !secret.isEmpty {
            return PluginEndpoint(kind: kind, host: address, port: port(s["port"]), secret: secret, http2: http2)
        }
        if let v = (settings["vnext"] as? [[String: Any]])?.first,
           let address = v["address"] as? String,
           let id = ((v["users"] as? [[String: Any]])?.first?["id"] as? String)?.lowercased(), !address.isEmpty, !id.isEmpty {
            return PluginEndpoint(kind: kind, host: address, port: port(v["port"]), secret: id, http2: http2)
        }
        return nil
    }

    private static func port(_ value: Any?) -> Int {
        (value as? Int) ?? Int((value as? String) ?? "") ?? 0
    }

    /// The config to run for a server: as is, or MASQUE rewritten for Xray's own client.
    /// Throws for protocols the iOS app can't run yet.
    static func prepare(_ server: Server) throws -> String {
        guard let json = server.xrayJson, let tag = server.proxyTag else {
            throw SubscriptionError(message: "Обновите подписку: потяните список серверов вниз")
        }
        guard let ep = endpoint(server) else { return json }
        guard ep.kind.supportedOnIOS else {
            throw SubscriptionError(message: "\(ep.kind.title) пока не работает на iPhone. Выберите другой сервер")
        }
        return withMasque(json, tag: tag, ep)
    }

    /// The server's config with its proxy outbound turned into Xray's own MASQUE client.
    static func withMasque(_ json: String, tag: String, _ ep: PluginEndpoint) -> String {
        guard var cfg = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              var outbounds = cfg["outbounds"] as? [[String: Any]] else { return json }
        for i in outbounds.indices where outbounds[i]["tag"] as? String == tag {
            var tls: [String: Any] = ["serverName": ep.host, "alpn": [ep.http2 ? "h2" : "h3"]]
            // Firefox: Russian DPI stalls the large Chrome ClientHello.
            if ep.http2 { tls["fingerprint"] = "firefox" }
            outbounds[i] = [
                "tag": tag,
                "protocol": "masque",
                "settings": ["address": ep.host, "port": ep.port],
                "streamSettings": [
                    "network": "masque", "security": "tls", "tlsSettings": tls,
                    "masqueSettings": ["user": ep.user, "pass": ep.password],
                ],
            ]
        }
        cfg["outbounds"] = outbounds
        return (try? JSONSerialization.data(withJSONObject: cfg)).map { String(decoding: $0, as: UTF8.self) } ?? json
    }
}
