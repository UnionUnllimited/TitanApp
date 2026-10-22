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
        // How the last run ended, read before a stop can write over it (a stop may come while
        // the network settings are still being set).
        queue.async { self.noteLastRun() }
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
                    self.watchMemory()
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
            memoryTimer?.cancel()
            memoryTimer = nil
            pressure?.cancel()
            pressure = nil
            running = nil
            _ = Self.invoke("stopXray")
            stopPlugin()
            // Already done at the start as a rule; a marker left by a killed run is counted first.
            noteLastRun()
            let why = Self.stopReasons[reason.rawValue] ?? "причина \(reason.rawValue)"
            note("остановлен: \(why)")
            removeMarker()
            completionHandler()
        }
    }

    private var pluginHandle: Int?

    /// Технический режим asks what the tunnel's TCP Fast Open dials did ("tfoStats": the
    /// kernel's record of each one, from the xray-tfo patch) and its memory ("mem").
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        switch String(decoding: messageData, as: UTF8.self) {
        case "tfoStats":
            completionHandler?(Data(TitanInvoke("{\"method\":\"tfoStats\"}").utf8))
        case "mem":
            // On the queue: the counters are its.
            queue.async { [self] in completionHandler?(memoryReport()) }
        default:
            completionHandler?(nil)
        }
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

    /// Xray (and the protocol client) again on the network there is now, or on the same one
    /// to free memory (the watchdog). A failure is tried again a little later; the tunnel ends
    /// only when it keeps failing.
    private func restart(memory: Bool = false) {
        pendingRestart = nil
        guard let run = running, !stopped else { return }
        reasserting = true
        // No network now: its next report restarts it; not a failure.
        guard network != nil else { return }
        // A retry isn't another restart.
        if memory { memoryRestarts += 1 } else if failures == 0 { networkRestarts += 1 }
        lastRestart = Date()
        let before = Self.memoryNow().footprint
        _ = Self.invoke("stopXray")
        stopPlugin()
        // What the old run held goes back to iOS now (Go keeps freed pages a while).
        _ = TitanInvoke("{\"method\":\"freeMemory\"}")
        do {
            if let plugin = run.plugin { try startPlugin(plugin) }
            try startXray(run.config)
            failures = 0
            reasserting = false
            let after = Self.memoryNow().footprint
            log.info("restarted (\(memory ? "memory" : "network", privacy: .public)): \(before / Self.mib, privacy: .public) -> \(after / Self.mib, privacy: .public) MiB")
            if memory { note("перезапуск по памяти: \(Self.mb(before)) → \(Self.mb(after))") }
        } catch {
            failures += 1
            let n = failures
            log.error("restart \(n, privacy: .public) failed: \(error.localizedDescription, privacy: .public)")
            guard n <= Self.retryDelays.count else {
                stopped = true
                note("завершён: \(error.localizedDescription)")
                removeMarker()
                cancelTunnelWithError(error)
                return
            }
            scheduleRestart(after: Self.retryDelays[n - 1])
        }
    }

    // MARK: Memory

    /// iOS kills a packet tunnel over its memory limit (about 50 MB) without a word. Close to it
    /// Xray (and the protocol client) start again on the same network, the VPN reasserting a
    /// moment: the old run's connections, buffers and UDP flows go. The watchdog never ends the
    /// tunnel (it can't know if Не отключать VPN would bring it back); a kill is left to that.
    private var memoryTimer: DispatchSourceTimer?
    private var pressure: DispatchSourceMemoryPressure?
    private var checkEvery: TimeInterval = 0
    /// Any restart, for the network or memory: a memory one comes at most once in 30 s.
    private var lastRestart = Date.distantPast
    private var memoryRestarts = 0
    private var networkRestarts = 0
    private var peak: Int64 = 0
    private var lastMarker = Date.distantPast
    private var record = RunRecord()
    /// memory.json read (noteLastRun): nothing writes it before.
    private var recordLoaded = false

    private static let mib: Int64 = 1 << 20
    /// Above it the checks and the marker come every second.
    private static let busy = 35 * mib

    private func watchMemory() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.setEventHandler { [weak self] in self?.checkMemory() }
        memoryTimer = timer
        checkEvery = 0
        checkMemory()
        timer.resume()
        // System-wide pressure: Go's free pages back at once, and a restart sooner on critical.
        let source = DispatchSource.makeMemoryPressureSource(eventMask: [.warning, .critical], queue: queue)
        source.setEventHandler { [weak self] in
            guard let self, let event = self.pressure?.data else { return }
            self.log.notice("memory pressure: \(event.contains(.critical) ? "critical" : "warning", privacy: .public)")
            _ = TitanInvoke("{\"method\":\"freeMemory\"}")
            self.checkMemory(critical: event.contains(.critical))
        }
        source.resume()
        pressure = source
    }

    /// Every 10 s, every second above 35 MiB, every 200 ms within 10 MiB of the limit; within
    /// 5 MiB (or within 10 on critical pressure) Xray starts again.
    private func checkMemory(critical: Bool = false) {
        guard !stopped, running != nil else { return }
        let m = Self.memoryNow()
        peak = max(peak, m.footprint)
        let armed = m.footprint >= m.limit - 10 * Self.mib
        if Date().timeIntervalSince(lastMarker) >= (m.footprint > Self.busy ? 0.9 : 9) { writeMarker(m.footprint) }
        let every: TimeInterval = armed ? 0.2 : m.footprint > Self.busy ? 1 : 10
        if every != checkEvery {
            checkEvery = every
            memoryTimer?.schedule(deadline: .now() + every, repeating: every, leeway: .milliseconds(every < 1 ? 50 : 500))
        }
        guard m.footprint >= m.limit - 5 * Self.mib || (critical && armed) else { return }
        // A restart waiting (it frees as much), one just now, or no network to start on: not now.
        guard pendingRestart == nil, network != nil, Date().timeIntervalSince(lastRestart) >= 30 else { return }
        log.notice("memory: \(m.footprint / Self.mib, privacy: .public) of \(m.limit / Self.mib, privacy: .public) MiB, restarting")
        restart(memory: true)
    }

    /// The footprint (what jetsam counts) and the limit: the footprint and what iOS says is left,
    /// or 50 MiB if it doesn't say.
    private static func memoryNow() -> (footprint: Int64, limit: Int64, remaining: Int64, available: Int64) {
        let m = titan_mem_read()
        let left = m.available > 0 ? m.available : m.remaining
        return (m.footprint, left > 0 ? m.footprint + left : 50 * mib, m.remaining, m.available)
    }

    /// Технический режим's «Память туннеля».
    private func memoryReport() -> Data? {
        let m = Self.memoryNow()
        let go = (try? JSONSerialization.jsonObject(with: Data(TitanInvoke("{\"method\":\"memStats\"}").utf8))) as? [String: Any]
        var out: [String: Any] = [
            "footprint": m.footprint, "peak": max(peak, m.footprint), "limit": m.limit,
            "remaining": m.remaining, "available": m.available, "go": go ?? [:],
            "memoryRestarts": memoryRestarts, "networkRestarts": networkRestarts,
            "kills": record.kills, "log": record.log,
        ]
        if let at = record.lastKill {
            out["lastKill"] = at.timeIntervalSince1970
            out["lastKillFootprint"] = record.lastKillFootprint
        }
        return try? JSONSerialization.data(withJSONObject: out)
    }

    // MARK: How runs ended

    /// The tunnel's runs across its processes, in its caches: running.json is there while one
    /// runs; still there at a start, the last process was killed (by iOS, for memory as a rule:
    /// a JetsamEvent in Аналитика) or crashed.
    private struct Marker: Codable {
        var at: Date
        var footprint: Int64
    }

    private struct RunRecord: Codable {
        var kills = 0
        var lastKill: Date?
        var lastKillFootprint: Int64 = 0
        /// Stops, kills and memory restarts, the newest last.
        var log: [String] = []
    }

    private static let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        ?? FileManager.default.temporaryDirectory
    private static let markerURL = dir.appendingPathComponent("running.json")
    private static let recordURL = dir.appendingPathComponent("memory.json")

    private func noteLastRun() {
        guard !recordLoaded else { return }
        recordLoaded = true
        if let data = try? Data(contentsOf: Self.recordURL), let saved = try? JSONDecoder().decode(RunRecord.self, from: data) {
            record = saved
        }
        guard let data = try? Data(contentsOf: Self.markerURL) else { return }
        let marker = try? JSONDecoder().decode(Marker.self, from: data)
        record.kills += 1
        record.lastKill = marker?.at ?? Date()
        record.lastKillFootprint = marker?.footprint ?? 0
        note("завершён системой или упал, было \(Self.mb(record.lastKillFootprint))", at: record.lastKill ?? Date())
        removeMarker()
    }

    private func note(_ text: String, at date: Date = Date()) {
        record.log = Array((record.log + ["\(Self.stamp.string(from: date)) \(text)"]).suffix(20))
        if let data = try? JSONEncoder().encode(record) { try? data.write(to: Self.recordURL, options: .atomic) }
    }

    private func writeMarker(_ footprint: Int64) {
        lastMarker = Date()
        if let data = try? JSONEncoder().encode(Marker(at: lastMarker, footprint: footprint)) {
            try? data.write(to: Self.markerURL, options: .atomic)
        }
    }

    private func removeMarker() {
        try? FileManager.default.removeItem(at: Self.markerURL)
    }

    private static func mb(_ bytes: Int64) -> String {
        String(format: "%.1f МБ", Double(bytes) / Double(mib))
    }

    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "dd.MM HH:mm:ss"
        return f
    }()

    /// NEProviderStopReason, the usual ones.
    private static let stopReasons: [Int: String] = [
        1: "выключен", 2: "ошибка туннеля", 3: "нет сети", 4: "смена сети", 5: "профиль выключен",
        7: "ошибка настроек", 8: "простой", 9: "профиль выключен", 10: "профиль удалён",
        11: "заменён другим VPN", 12: "выход пользователя", 13: "смена пользователя",
        14: "не удалось подключиться", 15: "сон", 16: "обновление приложения",
    ]

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
