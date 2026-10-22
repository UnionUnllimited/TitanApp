import Darwin
import LibXray
import Network
import NetworkExtension
import os

/// The VPN itself: iOS gives us a utun interface, Xray reads and writes its packets
/// directly (Xray's TUN inbound in iOS mode, xray.tun.fd) — the same as on Android.
/// The tunnel's own connections to the servers don't go through the tunnel (iOS keeps
/// the provider's sockets outside it).
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let log = Logger(subsystem: "com.titanvps.ios", category: "tunnel")

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let config = (options?["config"] as? String)
            ?? ((protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration?["config"] as? String)
        guard let config else {
            completionHandler(Self.error("Нет настроек сервера. Подключитесь из приложения"))
            return
        }
        // TUIC / AnyTLS / ShadowTLS / SSH / Mieru: their client (package titan) on the local
        // SOCKS port the config's proxy outbound points at.
        let plugin = (options?["plugin"] as? String)
            ?? ((protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration?["plugin"] as? String)
        setTunnelNetworkSettings(Self.networkSettings()) { [weak self] error in
            guard let self else { return }
            if let error {
                completionHandler(error)
                return
            }
            // On the network queue, as restarts and stop: never two of them at once.
            self.queue.async {
                guard !self.stopped else { completionHandler(Self.error("VPN выключен")); return }
                do {
                    if let plugin { try self.startPlugin(plugin) }
                    try self.startXray(config)
                    self.running = (config, plugin)
                    self.watchNetwork()
                    completionHandler(nil)
                } catch {
                    self.log.error("start failed: \(error.localizedDescription, privacy: .public)")
                    self.stopPlugin()
                    completionHandler(error)
                }
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        log.info("stop: \(reason.rawValue, privacy: .public)")
        // On the network queue: a restart under way ends first, and none starts after.
        queue.async { [self] in
            stopped = true
            pendingRestart?.cancel()
            pendingRestart = nil
            pathMonitor?.cancel()
            pathMonitor = nil
            running = nil
            _ = Self.invoke("stopXray")
            stopPlugin()
            completionHandler()
        }
    }

    private var pluginHandle: Int?

    /// Технический режим asks what the tunnel's TCP Fast Open dials did: the kernel's record
    /// of each one (data in the SYN, taken by the server or not), from the xray-tfo patch.
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard String(decoding: messageData, as: UTF8.self) == "tfoStats" else {
            completionHandler?(nil)
            return
        }
        completionHandler?(Data(TitanInvoke("{\"method\":\"tfoStats\"}").utf8))
    }

    // MARK: Network changes

    /// What runs now, to start again on a new network.
    private var running: (config: String, plugin: String?)?
    private var pathMonitor: NWPathMonitor?
    /// Start, stop, network reports and restarts all run on it.
    private let queue = DispatchQueue(label: "com.titanvps.ios.tunnel.network")
    private var network: String?
    private var pendingRestart: DispatchWorkItem?
    /// stopTunnel came: nothing starts again.
    private var stopped = false
    /// Restarts in a row that failed while there was a network.
    private var failures = 0
    /// Waits between tries after a failed restart: about a minute in all, then the tunnel ends
    /// and Не отключать VPN has iOS start it again.
    private static let retryDelays: [TimeInterval] = [1, 2, 4, 8, 15, 30]

    /// After a reboot iOS starts the VPN before the network (LTE above all) is really up,
    /// and on a Wi-Fi ↔ LTE switch the old one goes away: connections Xray opened then
    /// hang until they time out, so the VPN "works only after a while". When the network
    /// the tunnel goes out through appears or changes, Xray (and the protocol client) start
    /// again on it with fresh connections, the VPN reasserting (Подключаемся…) meanwhile.
    /// The utun fd stays NetworkExtension's: each Xray gets a copy of it (startXray).
    private func watchNetwork() {
        pathMonitor?.cancel()
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in self?.networkChanged(path) }
        monitor.start(queue: queue)
        pathMonitor = monitor
    }

    private func networkChanged(_ path: Network.NWPath) {
        // The physical network the traffic goes out through: the first one iOS prefers
        // (our own utun is type .other). A second one coming and going doesn't count.
        let primary = path.availableInterfaces.first { $0.type != NWInterface.InterfaceType.other && $0.type != NWInterface.InterfaceType.loopback }
        let now = path.status == .satisfied ? primary.map { "\($0.type):\($0.name)" } : nil
        let before = network
        network = now
        // The first report is the network we started on (or none yet).
        guard reported else { reported = true; return }
        // No network: nothing to do until one comes; the same one: nothing to do.
        guard let now, now != before else { return }
        log.info("network: \(before ?? "none", privacy: .public) -> \(now, privacy: .public)")
        // A new network: its tries count from the start.
        failures = 0
        // A moment for the network to settle (several reports come at once).
        scheduleRestart(after: 1.5)
    }
    private var reported = false

    /// The newest one replaces one still waiting.
    private func scheduleRestart(after delay: TimeInterval) {
        pendingRestart?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.restart() }
        pendingRestart = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    /// Xray (and the protocol client) again on the network there is now. A failure is tried
    /// again a little later; the tunnel ends only when it keeps failing.
    private func restart() {
        pendingRestart = nil
        guard let run = running, !stopped else { return }
        reasserting = true
        // No network now: its next report restarts it; not a failure.
        guard network != nil else { return }
        _ = Self.invoke("stopXray")
        do {
            if let plugin = run.plugin { try startPlugin(plugin) }
            try startXray(run.config)
            failures = 0
            reasserting = false
            log.info("restarted on the new network")
        } catch {
            failures += 1
            let n = failures
            log.error("restart \(n, privacy: .public) failed: \(error.localizedDescription, privacy: .public)")
            guard n <= Self.retryDelays.count else {
                stopped = true
                cancelTunnelWithError(error)
                return
            }
            scheduleRestart(after: Self.retryDelays[n - 1])
        }
    }

    private func startPlugin(_ request: String) throws {
        stopPlugin()
        let obj = (try? JSONSerialization.jsonObject(with: Data(TitanInvoke(request).utf8))) as? [String: Any] ?? [:]
        guard let handle = obj["handle"] as? Int else {
            throw Self.error("Клиент протокола не запустился: \((obj["error"] as? String) ?? "нет ответа")")
        }
        pluginHandle = handle
    }

    private func stopPlugin() {
        guard let handle = pluginHandle else { return }
        _ = TitanInvoke("{\"method\":\"stop\",\"handle\":\(handle)}")
        pluginHandle = nil
    }

    private static func networkSettings() -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "254.1.1.1")
        let ipv4 = NEIPv4Settings(addresses: ["172.19.0.1"], subnetMasks: ["255.255.255.252"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4
        let ipv6 = NEIPv6Settings(addresses: ["fdfe:dcba:9876::1"], networkPrefixLengths: [126])
        ipv6.includedRoutes = [NEIPv6Route.default()]
        settings.ipv6Settings = ipv6
        // Port 53 of this address is hijacked into Xray's DNS (routing rule in the config).
        let dns = NEDNSSettings(servers: ["172.19.0.2"])
        dns.matchDomains = [""]
        settings.dnsSettings = dns
        settings.mtu = NSNumber(value: XrayTunnelMTU)
        return settings
    }

    /// The utun fd NetworkExtension opened, found at the first start.
    private var tunnelFd: Int32 = -1

    private func startXray(_ config: String) throws {
        if tunnelFd < 0 { tunnelFd = titan_tunnel_fd() }
        guard tunnelFd >= 0 else { throw Self.error("Не найден интерфейс туннеля") }
        guard var cfg = (try? JSONSerialization.jsonObject(with: Data(config.utf8))) as? [String: Any] else {
            throw Self.error("Не удалось прочитать настройки сервера")
        }
        // Xray (Go) wraps its fd in an os.File that closes it once that Xray is stopped and
        // collected: with the tunnel's own fd that ended the VPN a little after a Wi-Fi ↔ LTE
        // restart. Each run gets a copy, and the copy is Go's: never closed here (a failed run
        // may already own it; at most one fd leaks per try).
        let fd = dup(tunnelFd)
        guard fd >= 0 else { throw Self.error("Не найден интерфейс туннеля") }
        var env = cfg["env"] as? [String: Any] ?? [:]
        env["xray.tun.fd"] = String(fd)
        // No geo files on iOS (the config has none); Xray still wants an asset dir.
        env["xray.location.asset"] = FileManager.default.temporaryDirectory.path
        cfg["env"] = env
        let json = String(decoding: try JSONSerialization.data(withJSONObject: cfg), as: UTF8.self)
        if let err = Self.invoke("runXray", payload: ["xrayJson": json]) { throw Self.error(err) }
    }

    /// libXray's single entry point (API v3); returns the error text or nil.
    @discardableResult
    private static func invoke(_ method: String, payload: [String: Any] = [:]) -> String? {
        let request: [String: Any] = ["apiVersion": 3, "method": method, "payload": payload]
        guard let data = try? JSONSerialization.data(withJSONObject: request) else { return "bad request" }
        let response = LibXrayInvoke(String(decoding: data, as: UTF8.self))
        guard let obj = (try? JSONSerialization.jsonObject(with: Data(response.utf8))) as? [String: Any] else { return "bad response" }
        if obj["success"] as? Bool == true { return nil }
        return (obj["error"] as? String) ?? "\(method) failed"
    }

    private static func error(_ text: String) -> NSError {
        NSError(domain: "com.titanvps.ios.tunnel", code: 1, userInfo: [NSLocalizedDescriptionKey: text])
    }
}

/// Same as the app's XrayRunConfig.mtu.
let XrayTunnelMTU = 1500
