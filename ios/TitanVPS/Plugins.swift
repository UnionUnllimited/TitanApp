import CryptoKit
import Foundation
import LibXray
import Network

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

    /// Naive is sing-box's naive outbound on Cronet (Chromium's network stack, as NaiveProxy).
    var supportedOnIOS: Bool { true }

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
    /// TUIC needs a UUID too: the second half of the hash.
    var tuicUuid: String {
        let h = Array(derived.dropFirst(32).prefix(32))
        return [h[0..<8], h[8..<12], h[12..<16], h[16..<20], h[20..<32]].map { String($0) }.joined(separator: "-")
    }
    /// ShadowTLS: "server key:user key" of the Shadowsocks 2022 inside (as the node sync).
    var ss2022Password: String { "\(Self.ss2022Key("titan-ss2022-server")):\(Self.ss2022Key("titan-ss2022:\(secret)"))" }

    private static func ss2022Key(_ text: String) -> String {
        Data(SHA256.hash(data: Data(text.utf8)).prefix(16)).base64EncodedString()
    }
}

/// A server's config ready to run, and the client to start for it first (a titan request).
struct PreparedServer {
    let json: String
    let plugin: String?
    /// sing-box's log (warnings and errors), shown under a server whose ping failed.
    var log: String? = nil

    /// The end of the client's log, if it wrote anything.
    func logTail(_ limit: Int = 300) -> String? {
        guard let log, let text = try? String(contentsOfFile: log, encoding: .utf8) else { return nil }
        let lines = text.split(separator: "\n").map { line in
            // "+0300 2026-10-13 17:24:01 ERROR [123 1ms] outbound/tuic[proxy]: …" → "ERROR outbound/tuic[proxy]: …"
            line.replacingOccurrences(of: #"^[+-]\d{4} \S+ \S+ "#, with: "", options: .regularExpression)
                .replacingOccurrences(of: #"\[\d+ [^\]]*\] "#, with: "", options: .regularExpression)
        }
        let tail = lines.suffix(3).joined(separator: "; ")
        return tail.isEmpty ? nil : String(tail.suffix(limit))
    }
}

/// Our protocol clients in the framework (package titan): start / stop by JSON request.
enum Titan {
    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    @discardableResult
    static func invoke(_ request: [String: Any]) throws -> [String: Any] {
        let data = try JSONSerialization.data(withJSONObject: request)
        let response = TitanInvoke(String(decoding: data, as: UTF8.self))
        let obj = (try? JSONSerialization.jsonObject(with: Data(response.utf8))) as? [String: Any] ?? [:]
        if let error = obj["error"] as? String { throw Failure(message: error) }
        return obj
    }

    static func freePort() throws -> Int {
        guard let port = try invoke(["method": "freePort"])["port"] as? Int else { throw Failure(message: "нет свободного порта") }
        return port
    }

    /// Starts a client from a prepared request; returns its handle.
    static func start(_ request: String) throws -> Int {
        guard let req = (try? JSONSerialization.jsonObject(with: Data(request.utf8))) as? [String: Any],
              let handle = try invoke(req)["handle"] as? Int else { throw Failure(message: "клиент не запустился") }
        return handle
    }

    static func stop(_ handle: Int) {
        _ = try? invoke(["method": "stop", "handle": handle])
    }
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

    /// ShadowTLS handshakes with this real site (the node's "handshake" server).
    static let shadowTlsSni = "www.amd.com"

    /// The config to run for a server: as is, MASQUE rewritten for Xray's own client, or its
    /// proxy pointed at a local SOCKS port with the client to start there.
    /// forTunnel: for the VPN extension, which can't write into the app's folders (no log file).
    static func prepare(_ server: Server, forTunnel: Bool = false) throws -> PreparedServer {
        guard let json = server.xrayJson, let tag = server.proxyTag else {
            throw SubscriptionError(message: "Обновите подписку: потяните список серверов вниз")
        }
        guard let ep = endpoint(server) else { return PreparedServer(json: json, plugin: nil) }
        guard ep.kind.supportedOnIOS else {
            throw SubscriptionError(message: "\(ep.kind.title) пока не работает на iPhone. Выберите другой сервер")
        }
        if ep.kind == .masque { return PreparedServer(json: withMasque(json, tag: tag, ep), plugin: nil) }
        // The clients' own DNS doesn't work on iOS (no resolv.conf): they get the address
        // resolved here by the system (TLS names stay the host's), as Mieru on Android.
        let address = resolve(ep.host)
        let port = try Titan.freePort()
        let log = ep.kind == .mieru || forTunnel ? nil : (NSTemporaryDirectory() as NSString).appendingPathComponent("singbox-\(port).log")
        if let log { try? FileManager.default.removeItem(atPath: log) }
        let request: [String: Any] = ep.kind == .mieru
            ? ["method": "startMieru", "profile": mieruProfile(ep, address: address), "port": port]
            : ["method": "startSingBox", "config": singBoxConfig(ep, port: port, log: log, address: address)]
        let plugin = String(decoding: try JSONSerialization.data(withJSONObject: request), as: UTF8.self)
        return PreparedServer(json: withLocalSocks(json, tag: tag, port: port), plugin: plugin, log: log)
    }

    /// The same config with its proxy outbound sent to our local SOCKS on port.
    static func withLocalSocks(_ json: String, tag: String, port: Int) -> String {
        guard var cfg = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any],
              var outbounds = cfg["outbounds"] as? [[String: Any]] else { return json }
        for i in outbounds.indices where outbounds[i]["tag"] as? String == tag {
            outbounds[i] = [
                "tag": tag,
                "protocol": "socks",
                "settings": ["servers": [["address": "127.0.0.1", "port": port]]],
            ]
        }
        cfg["outbounds"] = outbounds
        return (try? JSONSerialization.data(withJSONObject: cfg)).map { String(decoding: $0, as: UTF8.self) } ?? json
    }

    /// sing-box as the client for TUIC / AnyTLS / ShadowTLS / SSH (same as Android's).
    /// The host's address by the system resolver (IPv4 first); the host itself if it fails.
    static func resolve(_ host: String) -> String {
        if IPv4Address(host) != nil || IPv6Address(host) != nil { return host }
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else { return host }
        defer { freeaddrinfo(first) }
        var v4: String?
        var v6: String?
        var p: UnsafeMutablePointer<addrinfo>? = first
        while let ai = p {
            var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(ai.pointee.ai_addr, ai.pointee.ai_addrlen, &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: buf)
                if ai.pointee.ai_family == AF_INET, v4 == nil { v4 = ip }
                if ai.pointee.ai_family == AF_INET6, v6 == nil { v6 = ip }
            }
            p = ai.pointee.ai_next
        }
        return v4 ?? v6 ?? host
    }

    static func singBoxConfig(_ ep: PluginEndpoint, port: Int, log: String? = nil, address: String? = nil) -> String {
        let server = address ?? ep.host
        func tls(_ sni: String) -> [String: Any] { ["enabled": true, "server_name": sni] }
        // Firefox: Russian DPI stalls the large Chrome ClientHello.
        let firefox: [String: Any] = ["enabled": true, "fingerprint": "firefox"]
        var outbounds: [[String: Any]] = []
        switch ep.kind {
        case .tuic:
            var t = tls(ep.host)
            t["alpn"] = ["h3"]
            outbounds.append([
                "type": "tuic", "tag": "proxy", "server": server, "server_port": ep.port,
                "uuid": ep.tuicUuid, "password": ep.password,
                "congestion_control": "bbr", "udp_relay_mode": "native", "tls": t,
            ])
        case .anytls:
            var t = tls(ep.host)
            t["utls"] = firefox
            outbounds.append(["type": "anytls", "tag": "proxy", "server": server, "server_port": ep.port, "password": ep.password, "tls": t])
        case .shadowtls:
            // ShadowTLS v3: a real TLS handshake with the cover site, then Shadowsocks 2022
            // whose per-user key tells the node who it is.
            var t = tls(shadowTlsSni)
            t["utls"] = firefox
            outbounds.append([
                "type": "shadowsocks", "tag": "proxy", "method": "2022-blake3-aes-128-gcm",
                "password": ep.ss2022Password, "detour": "shadowtls",
            ])
            outbounds.append([
                "type": "shadowtls", "tag": "shadowtls", "server": server, "server_port": ep.port,
                "version": 3, "password": ep.password, "tls": t,
            ])
        case .naive:
            // NaiveProxy: HTTP/2 CONNECT through Chromium's stack to the node's naive inbound.
            outbounds.append([
                "type": "naive", "tag": "proxy", "server": server, "server_port": ep.port,
                "username": ep.user, "password": ep.password, "tls": tls(ep.host),
            ])
        case .ssh:
            // SSH tunnel (like "ssh -D") to the node's gateway, looking like stock OpenSSH.
            outbounds.append([
                "type": "ssh", "tag": "proxy", "server": server, "server_port": ep.port,
                "user": ep.user, "password": ep.password,
                "client_version": "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.5",
            ])
        default:
            break
        }
        var logOptions: [String: Any] = ["level": "warn", "timestamp": false]
        if let log { logOptions["output"] = log }
        let cfg: [String: Any] = [
            "log": logOptions,
            "inbounds": [["type": "mixed", "listen": "127.0.0.1", "listen_port": port]],
            "outbounds": outbounds,
            "route": ["final": "proxy"],
        ]
        return (try? JSONSerialization.data(withJSONObject: cfg)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    }

    /// Mieru's client profile (same as Android's, without the app-level fields).
    static func mieruProfile(_ ep: PluginEndpoint, address: String? = nil) -> String {
        let host = address ?? ep.host
        var server: [String: Any] = ["portBindings": [["port": ep.port, "protocol": "TCP"]]]
        if IPv4Address(host) != nil || IPv6Address(host) != nil { server["ipAddress"] = host } else { server["domainName"] = host }
        let profile: [String: Any] = [
            "profileName": "titan",
            "user": ["name": ep.user, "password": ep.password],
            "servers": [server],
            "mtu": 1400,
        ]
        return (try? JSONSerialization.data(withJSONObject: profile)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
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
