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
        let by = Self.startedBy(options)
        // iOS's own VPN switch turned the VPN off with Не отключать VPN on, and on demand starts it
        // again in the same boot: on demand goes off, this start doesn't run (holdOnDemand). Any
        // start uses the stop's mark up; one from a boot before (a power-off) starts as ever.
        let userStop = Self.takeUserStop()
        if #available(iOS 17.0, *), let userStop, case .system(onDemand: true) = by, Self.sameBoot(userStop) {
            holdOnDemand(completionHandler)
            return
        }
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
                    self.setGoLimit()
                    self.measureAfter(restartAt: nil, line: nil)
                    self.watchNetwork()
                    self.watchMemory()
                    completionHandler(nil)
                    self.note("запущен: \(by.text)")
                    self.giveOnDemandBack(by)
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
        // Turned off by the user: maybe iOS's own switch with Не отключать VPN on (checkOnDemand).
        var check: ProfileWork<Bool>?
        if #available(iOS 17.0, *), reason == .userInitiated { check = Self.checkOnDemand() }
        // On the network queue: a restart under way ends first, and none starts after.
        queue.async { [self, check] in
            stopped = true
            pendingRestart?.cancel()
            pendingRestart = nil
            pathMonitor?.cancel()
            pathMonitor = nil
            memoryTimer?.cancel()
            memoryTimer = nil
            pressure?.cancel()
            pressure = nil
            pendingAfter?.cancel()
            pendingAfter = nil
            // The profile read before the tunnel goes: iOS may end the process once the stop is
            // over. Xray still forwards meanwhile. 3 s at most: a load from the extension that never
            // answered was seen after an off in iOS Settings (Apple Developer Forums 688076), and
            // the VPN would stay «disconnecting» until iOS killed us. The mark is written here, not
            // by a late load: a start after this stop must find it or never.
            var outcome: String?
            if let check {
                switch check.wait(seconds: 3) {
                case true?:
                    let boot = Self.bootTime
                    if boot != 0 { try? Data(String(boot).utf8).write(to: Self.userStopURL, options: .atomic) }
                    outcome = "«Не отключать VPN» включён: следующий запуск по запросу до перезагрузки отклонится"
                case false?: break
                case nil: outcome = "«Не отключать VPN»: настройки VPN не ответили за 3 с"
                }
            }
            running = nil
            _ = Self.invoke("stopXray")
            // Its pooled connections closed now, not when the process goes.
            _ = TitanInvoke("{\"method\":\"closeTransports\"}")
            stopPlugin()
            // Already done at the start as a rule; a marker left by a killed run is counted first.
            noteLastRun()
            let why = Self.stopReasons[reason.rawValue] ?? "причина \(reason.rawValue)"
            note("остановлен: \(why)")
            if let outcome { note(outcome) }
            removeMarker()
            completionHandler()
        }
    }

    // MARK: Не отключать VPN and iOS's own VPN switch

    /// Who started the tunnel, for the journal and for Не отключать VPN (giveOnDemandBack).
    private enum StartedBy {
        /// VPNController.connect: the config in the options.
        case app
        /// TunnelSwitch.start (Titan VPS in Пункт управления, Siri, the Live Activity): "by".
        case ourSwitch
        /// iOS: on demand, or its own VPN switch (Пункт управления, Настройки → VPN). "is-on-demand"
        /// is a key iOS adds to an on-demand start: not in Apple's documentation (startTunnel says
        /// options are nil then), but Mullvad (PacketTunnelOptions: "System automatically provides
        /// that flag") and DuckDuckGo (StartupOptions) read it. Without it: either.
        case system(onDemand: Bool)

        var text: String {
            switch self {
            case .app: return "приложением"
            case .ourSwitch: return "переключателем Titan VPS, Siri или Live Activity"
            case .system(onDemand: true): return "iOS по запросу («Не отключать VPN»)"
            case .system(onDemand: false): return "iOS: переключателем VPN в системе или по запросу"
            }
        }
    }

    private static func startedBy(_ options: [String: NSObject]?) -> StartedBy {
        if options?["config"] != nil { return .app }
        if options?["by"] != nil { return .ourSwitch }
        return .system(onDemand: (options?["is-on-demand"] as? NSNumber)?.boolValue == true)
    }

    /// Set while on demand is off because iOS's own VPN switch turned the VPN off (holdOnDemand):
    /// the next start by that switch gives it back. In the extension's own caches, as the journal.
    private static let heldURL = dir.appendingPathComponent("ondemand-held")

    /// Left by a stop by the user with on demand still on (checkOnDemand), used up by the next
    /// start: the boot it came in (bootTime).
    private static let userStopURL = dir.appendingPathComponent("user-stop")

    /// Work on the VPN profile from the extension, which a stop or a start waits for, a few
    /// seconds at most: a load from the extension that never answered was seen after an off in
    /// iOS Settings (Apple Developer Forums 688076).
    private final class ProfileWork<Value> {
        private let done = DispatchSemaphore(value: 0)
        private var value: Value?

        func finish(_ value: Value) {
            self.value = value
            done.signal()
        }

        /// What it came to; nil if it didn't end within [seconds].
        func wait(seconds: Double) -> Value? {
            done.wait(timeout: .now() + seconds) == .success ? value : nil
        }
    }

    /// Turned off by the user (NEProviderStopReason.userInitiated): whether on demand is still on
    /// in the profile. Every stop of ours saves it off before stopping the tunnel
    /// (VPNController.holdOnDemand, TunnelSwitch.stop), so on here means iOS's own switch:
    /// Пункт управления's VPN or Настройки → VPN, which run no code of the app. Left on, its
    /// Connect rule starts the VPN again at the next connection ("Users are unable to turn off the
    /// VPN from Control Center's stock VPN toggle or the system Settings app, as the onDemand rule
    /// causes the VPN to turn itself back on as soon as a network request is made", Apple
    /// Developer Forums 832054; the same in Algo's docs), and iOS doesn't turn on demand off
    /// itself. Only read here: a power-off or a reboot may stop the tunnel with userInitiated too
    /// (DTS on macOS, forums 750354: "Shut downs are user initiates after all", no API tells them
    /// apart), and on demand saved off then would keep the VPN off after the boot with nothing of
    /// ours left to run. So stop leaves a mark with the boot (userStopURL), and the decision is
    /// holdOnDemand's at on demand's next start, in the same boot only.
    @available(iOS 17.0, *)
    private static func checkOnDemand() -> ProfileWork<Bool> {
        let work = ProfileWork<Bool>()
        Task {
            let m = try? await NETunnelProviderManager.loadAllFromPreferences().first
            work.finish(m?.isOnDemandEnabled == true)
        }
        return work
    }

    /// The stop's mark (checkOnDemand), taken by any start; nil if there was none.
    private static func takeUserStop() -> Int? {
        guard let text = try? String(contentsOf: userStopURL, encoding: .utf8) else { return nil }
        try? FileManager.default.removeItem(at: userStopURL)
        return Int(text)
    }

    /// When the phone booted (kern.boottime, s; 0 if unread): a stop before a power-off or a
    /// reboot has another boot's.
    private static var bootTime: Int {
        var mib: [Int32] = [CTL_KERN, KERN_BOOTTIME]
        var tv = timeval()
        var size = MemoryLayout<timeval>.size
        return sysctl(&mib, 2, &tv, &size, nil, 0) == 0 ? Int(tv.tv_sec) : 0
    }

    /// [boot] is this boot's. A few seconds either way: a change of the phone's time moves
    /// kern.boottime with it. A reboot moves it by the uptime the stop came at and the boot.
    private static func sameBoot(_ boot: Int) -> Bool {
        let now = bootTime
        return boot != 0 && now != 0 && abs(now - boot) <= 10
    }

    /// Started by on demand, in the same boot, right after iOS's own switch turned the VPN off
    /// with on demand on (checkOnDemand's mark): that is the user's off, which on demand would
    /// undo. On demand is saved off and the start refused (the switch shows «Подключение» for a
    /// moment). A start after a reboot never comes here: the VPN comes back then, whatever stop
    /// reason iOS gave at the power-off.
    /// Apple documents the configuration API for the app only (DTS, forums 731233: "can only be
    /// used by the container app"); DuckDuckGo's VPN saves on demand off from its tunnel (at the
    /// stop) since April 2024 (BrowserServicesKit #790: "so that users can toggle the VPN from
    /// Settings.app and it doesn't immediately re-enable itself due to on-demand"), on iOS 17+
    /// only: on 16 the load "can raise an Objective-C exception". Relies on iOS's "is-on-demand"
    /// (StartedBy): without it the start counts as the switch's, and the VPN comes back as before.
    /// A failed save leaves things as before as well: on demand's next start runs.
    @available(iOS 17.0, *)
    private func holdOnDemand(_ completionHandler: @escaping (Error?) -> Void) {
        let work = ProfileWork<String>()
        Task {
            guard let m = try? await NETunnelProviderManager.loadAllFromPreferences().first else {
                work.finish("«Не отключать VPN» не снят: настройки VPN не прочитаны")
                return
            }
            guard m.isOnDemandEnabled else {
                work.finish("«Не отключать VPN» уже снят")
                return
            }
            m.isOnDemandEnabled = false
            do {
                try await m.saveToPreferences()
                try? Data().write(to: Self.heldURL, options: .atomic)
                work.finish("«Не отключать VPN» снят до следующего включения: VPN выключили в системе")
            } catch {
                work.finish("«Не отключать VPN» не снят: \(error.localizedDescription)")
            }
        }
        // Answered once the save is done: iOS would else try on demand again at once.
        queue.async { [self] in
            note("не запущен по запросу: VPN выключили в системе")
            note(work.wait(seconds: 3) ?? "«Не отключать VPN»: настройки VPN не ответили за 3 с")
            completionHandler(Self.error("VPN выключен в системе", code: Self.declinedCode))
        }
    }

    /// The error of a start holdOnDemand refused: the app shows Выкл for it, no error.
    private static let declinedCode = 2

    /// This session started with on demand off by holdOnDemand, and it isn't seen on again yet
    /// (giveOnDemandBack): VPNController.switchedOffInSettings asks ("onDemandHeld"), so that the
    /// hold isn't taken for «Подключать по запросу» switched off in iOS Settings. On the queue.
    private var onDemandHeld = false

    /// Up again after holdOnDemand: on demand back as it was, now that the VPN is up (never for a
    /// start that failed, which on demand would repeat). Started by iOS's own switch: that's the
    /// user turning it on, as DuckDuckGo turns on demand on for a start from Settings. By the app
    /// or our switch: they set it themselves (Не отключать VPN as switched in the app), only
    /// looked at. Started by on demand: it is on already. On the network queue.
    private func giveOnDemandBack(_ by: StartedBy) {
        guard FileManager.default.fileExists(atPath: Self.heldURL.path) else { return }
        try? FileManager.default.removeItem(at: Self.heldURL)
        guard #available(iOS 17.0, *) else { return }
        // Before completionHandler's block ends: no message of the app's is answered before this.
        onDemandHeld = true
        let bySystem: Bool
        if case .system = by { bySystem = true } else { bySystem = false }
        Task { [weak self] in
            guard let m = try? await NETunnelProviderManager.loadAllFromPreferences().first else { return }
            guard !m.isOnDemandEnabled else {
                self?.queue.async { self?.onDemandHeld = false }
                return
            }
            guard bySystem else { return }
            m.isOnDemandEnabled = true
            let saved = (try? await m.saveToPreferences()) != nil
            self?.queue.async {
                if saved { self?.onDemandHeld = false }
                self?.note(saved ? "«Не отключать VPN» снова включён: VPN включили в системе"
                    : "«Не отключать VPN» не включён снова: настройки VPN не сохранились")
            }
        }
    }

    private var pluginHandle: Int?

    /// Технический режим asks what the tunnel's TCP Fast Open dials did ("tfoStats": the
    /// kernel's record of each one, from the xray-tfo patch) and its memory ("mem"); the app
    /// whether on demand is held off in this session ("onDemandHeld").
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        let msg = String(decoding: messageData, as: UTF8.self)
        // Технический режим's ICMP feasibility check: off the queue (it blocks on recvfrom).
        if msg.hasPrefix("icmpProbe:") {
            let host = String(msg.dropFirst("icmpProbe:".count))
            DispatchQueue.global(qos: .userInitiated).async { completionHandler?(Self.icmpProbe(host)) }
            return
        }
        if msg.hasPrefix("icmpLoad:") {
            let host = String(msg.dropFirst("icmpLoad:".count))
            DispatchQueue.global(qos: .userInitiated).async { completionHandler?(Self.icmpLoad(host)) }
            return
        }
        switch msg {
        case "tfoStats":
            completionHandler?(Data(TitanInvoke("{\"method\":\"tfoStats\"}").utf8))
        case "mem":
            // On the queue: the counters are its.
            queue.async { [self] in completionHandler?(memoryReport()) }
        case "onDemandHeld":
            queue.async { [self] in completionHandler?(Data("{\"held\":\(onDemandHeld)}".utf8)) }
        default:
            completionHandler?(nil)
        }
    }

    // MARK: ICMP probe (feasibility spike)

    /// Технический режим → «ICMP»: whether the extension can open an unprivileged ICMP socket
    /// (SOCK_DGRAM, IPPROTO_ICMP — no root, a public API, as Apple's SimplePing does) and
    /// round-trip an echo to [host] from inside the Network Extension's sandbox. That single
    /// fact decides whether an on-device ICMP transport is worth building; it can't be known
    /// without running here, on a real phone, on Wi‑Fi and on cellular. Nothing of the VPN is
    /// touched: the socket leaves by the real interface (NECP keeps the extension's own sockets
    /// out of the tunnel), so turning Wi‑Fi off tests cellular. Returns JSON for the app.
    private static func icmpProbe(_ host: String, count: Int = 5) -> Data {
        func json(_ o: [String: Any]) -> Data { (try? JSONSerialization.data(withJSONObject: o)) ?? Data("{}".utf8) }
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)
        if fd < 0 {
            let e = errno
            return json(["opened": false, "errno": Int(e), "error": String(cString: strerror(e))])
        }
        defer { close(fd) }
        var tv = timeval(tv_sec: 2, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        let (target, resolveError) = icmpAddress(host)
        guard let sin = target else { return json(["opened": true, "resolved": false, "error": resolveError ?? ""]) }
        var addr = sockaddr_storage()
        let addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutablePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee = sin }
        }
        var received = 0
        var bestRTT = Double.greatestFiniteMagnitude
        var lastErr: String?
        for seq in 0..<count {
            // ICMP echo: type 8, code 0; the kernel fills the checksum and the identifier for a
            // datagram socket, so 0 is fine. 32 bytes of payload.
            var pkt = [UInt8](repeating: 0, count: 40)
            pkt[0] = 8
            pkt[6] = UInt8((seq >> 8) & 0xff); pkt[7] = UInt8(seq & 0xff)
            for i in 8..<pkt.count { pkt[i] = UInt8(0x61 + (i % 26)) }
            let start = Date()
            let sent = pkt.withUnsafeBytes { raw in
                withUnsafePointer(to: addr) { sa in
                    sa.withMemoryRebound(to: sockaddr.self, capacity: 1) { sap in
                        sendto(fd, raw.baseAddress, raw.count, 0, sap, addrLen)
                    }
                }
            }
            if sent < 0 { lastErr = String(cString: strerror(errno)); continue }
            var buf = [UInt8](repeating: 0, count: 1500)
            let n = recvfrom(fd, &buf, buf.count, 0, nil, nil)
            if n > 0 {
                received += 1
                bestRTT = min(bestRTT, Date().timeIntervalSince(start) * 1000)
            } else {
                lastErr = String(cString: strerror(errno))
            }
        }
        var out: [String: Any] = ["opened": true, "resolved": true, "sent": count, "received": received]
        if received > 0 { out["rtt_ms"] = Int(bestRTT.rounded()) }
        if let lastErr, received < count { out["error"] = lastErr }
        return json(out)
    }

    /// One IPv4 address for [host]: an address as it is (inet_pton), a name through getaddrinfo
    /// with no socket type or protocol in the hints — getaddrinfo knows no ICMP datagram socket
    /// and refuses SOCK_DGRAM with IPPROTO_ICMP even for a numeric address.
    private static func icmpAddress(_ host: String) -> (sockaddr_in?, String?) {
        var sin = sockaddr_in()
        sin.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        sin.sin_family = sa_family_t(AF_INET)
        if inet_pton(AF_INET, host, &sin.sin_addr) == 1 { return (sin, nil) }
        var hints = addrinfo(ai_flags: 0, ai_family: AF_INET, ai_socktype: 0,
                             ai_protocol: 0, ai_addrlen: 0, ai_canonname: nil, ai_addr: nil, ai_next: nil)
        var res: UnsafeMutablePointer<addrinfo>?
        let rc = getaddrinfo(host, nil, &hints, &res)
        guard rc == 0, let info = res, let sa = info.pointee.ai_addr else {
            if let res { freeaddrinfo(res) }
            return (nil, String(cString: gai_strerror(rc)))
        }
        sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin.sin_addr = $0.pointee.sin_addr }
        freeaddrinfo(res)
        return (sin, nil)
    }

    /// What the load test's sender and receiver threads share (under [lock]).
    private final class IcmpLoad: @unchecked Sendable {
        let lock = NSLock()
        var sentAt: [Double]
        var rtt: [Double]
        var stop = false
        init(_ total: Int) {
            sentAt = [Double](repeating: 0, count: total)
            rtt = [Double](repeating: -1, count: total)
        }
    }

    /// Технический режим → «ICMP под нагрузкой»: the carrier's limit on ICMP, before any tunnel is
    /// built on it. Echoes of 1200 B (an inner packet of a tunnel at MTU 1200) at 50, 100, 200, 400
    /// and 800 a second, 2 s each, stopping after a step that lost more than half: per step what
    /// came back, the latency and the goodput. Mobile carriers often cap ICMP by packets a second,
    /// so a single ping passing says little about a chatty inner protocol (mKCP) under load.
    private static func icmpLoad(_ host: String) -> Data {
        func json(_ o: [String: Any]) -> Data { (try? JSONSerialization.data(withJSONObject: o)) ?? Data("{}".utf8) }
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)
        if fd < 0 {
            let e = errno
            return json(["opened": false, "error": String(cString: strerror(e))])
        }
        defer { close(fd) }
        let (target, resolveError) = icmpAddress(host)
        guard let sin = target else { return json(["opened": true, "resolved": false, "error": resolveError ?? ""]) }
        var addr = sockaddr_storage()
        withUnsafeMutablePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee = sin }
        }
        let addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        var tv = timeval(tv_sec: 0, tv_usec: 200_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var buffer: Int32 = 1 << 20
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buffer, socklen_t(MemoryLayout<Int32>.size))

        let rates = [50, 100, 200, 400, 800]
        let stepSeconds = 2.0
        let payload = 1200
        let counts = rates.map { Int(Double($0) * stepSeconds) }
        let state = IcmpLoad(counts.reduce(0, +))
        let start = CFAbsoluteTimeGetCurrent()
        let magic: [UInt8] = [0x54, 0x4c, 0x44, 0x31] // "TLD1"

        // Replies: the datagram socket gives the IPv4 header too on Darwin (SimplePing skips it).
        let receiverDone = DispatchSemaphore(value: 0)
        Thread.detachNewThread {
            var buf = [UInt8](repeating: 0, count: 2048)
            while true {
                state.lock.lock(); let stop = state.stop; state.lock.unlock()
                if stop { break }
                let n = recvfrom(fd, &buf, buf.count, 0, nil, nil)
                guard n > 0 else { continue }
                var at = 0
                if n >= 20, buf[0] >> 4 == 4 { at = Int(buf[0] & 0x0f) * 4 }
                guard n >= at + 16, buf[at] == 0, Array(buf[(at + 8)..<(at + 12)]) == magic else { continue }
                let seq = Int(buf[at + 12]) << 24 | Int(buf[at + 13]) << 16 | Int(buf[at + 14]) << 8 | Int(buf[at + 15])
                let now = CFAbsoluteTimeGetCurrent() - start
                state.lock.lock()
                if seq < state.rtt.count, state.rtt[seq] < 0 { state.rtt[seq] = (now - state.sentAt[seq]) * 1000 }
                state.lock.unlock()
            }
            receiverDone.signal()
        }

        var pkt = [UInt8](repeating: 0x61, count: 8 + payload)
        pkt[0] = 8; pkt[1] = 0; pkt[2] = 0; pkt[3] = 0; pkt[4] = 0; pkt[5] = 0
        for i in 0..<4 { pkt[8 + i] = magic[i] }
        var seq = 0
        var steps: [[String: Any]] = []
        var sendError: String?
        for (index, pps) in rates.enumerated() {
            let first = seq
            let stepStart = CFAbsoluteTimeGetCurrent()
            for i in 0..<counts[index] {
                let due = stepStart + Double(i) / Double(pps)
                let wait = due - CFAbsoluteTimeGetCurrent()
                if wait > 0 { usleep(useconds_t(wait * 1_000_000)) }
                pkt[6] = UInt8((seq >> 8) & 0xff); pkt[7] = UInt8(seq & 0xff)
                pkt[12] = UInt8((seq >> 24) & 0xff); pkt[13] = UInt8((seq >> 16) & 0xff)
                pkt[14] = UInt8((seq >> 8) & 0xff); pkt[15] = UInt8(seq & 0xff)
                state.lock.lock(); state.sentAt[seq] = CFAbsoluteTimeGetCurrent() - start; state.lock.unlock()
                let sent = pkt.withUnsafeBytes { raw in
                    withUnsafePointer(to: addr) { sa in
                        sa.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, raw.baseAddress, raw.count, 0, $0, addrLen) }
                    }
                }
                if sent < 0, sendError == nil { sendError = String(cString: strerror(errno)) }
                seq += 1
            }
            // The step's last replies.
            usleep(600_000)
            state.lock.lock()
            let rtts = state.rtt[first..<seq].filter { $0 >= 0 }
            state.lock.unlock()
            let got = rtts.count
            var step: [String: Any] = ["pps": pps, "sent": seq - first, "received": got,
                                       "mbps": Double(got * payload * 8) / stepSeconds / 1_000_000]
            if got > 0 {
                step["rttAvg"] = Int((rtts.reduce(0, +) / Double(got)).rounded())
                step["rttMax"] = Int((rtts.max() ?? 0).rounded())
            }
            steps.append(step)
            if got * 2 < seq - first { break }
        }
        state.lock.lock(); state.stop = true; state.lock.unlock()
        _ = receiverDone.wait(timeout: .now() + 1)
        var out: [String: Any] = ["opened": true, "resolved": true, "payload": payload, "steps": steps]
        if let sendError { out["error"] = sendError }
        return json(out)
    }

    // MARK: Sleep

    /// The device going to sleep: the burst observatory's rounds stop (titan sleep). ~60 subjects
    /// at 5m × 5 are a probe every ~5 s, each a cold handshake on a connection of its own, and
    /// with the phone asleep they kept the cellular radio from going idle. The balancers keep
    /// their results, which don't age meanwhile; a restart's first check still probes (its new
    /// Xray has none). Nothing else changes: the tunnel keeps forwarding.
    override func sleep(completionHandler: @escaping () -> Void) {
        let reply = TitanInvoke("{\"method\":\"sleep\"}")
        log.info("sleep: \(reply, privacy: .public)")
        completionHandler()
    }

    /// The device woke: the rounds go on at once, after 5 min or more asleep with a wake round
    /// first (every subject once, 5-30 s after the wake, 4 at a time), so the balancers choose
    /// from what the network is now.
    override func wake() {
        let reply = TitanInvoke("{\"method\":\"wake\"}")
        log.info("wake: \(reply, privacy: .public)")
    }

    // MARK: Network changes

    /// What runs now, to start again on a new network.
    private var running: (config: String, plugin: String?)?
    private var pathMonitor: NWPathMonitor?
    /// Start, stop, network reports and restarts all run on it.
    private let queue = DispatchQueue(label: "com.titanvps.ios.tunnel.network")
    /// The network the traffic goes out through now (its interface, "type:name"); nil while
    /// there is none.
    private var network: String?
    /// A network: its interface ("type:name") and the interface's addresses, link-local ones
    /// left out.
    private typealias Net = (key: String, addresses: Set<String>)
    /// The last network there was: a network coming back is told by it from a new one. Nil
    /// until there was one.
    private var lastNetwork: Net?
    /// The network the running Xray was started on; nil if there was none.
    private var startedOn: Net?
    /// The physical interfaces ("type:name") of the last report with a network, the primary
    /// one among them.
    private var available: Set<String> = []
    /// When the network went away (Self.clock); nil while there is one.
    private var lostAt: TimeInterval?
    /// A network restart decided on and not done yet (it waits for a network and for the end
    /// of the 20 s since the last one): what for, for the journal.
    private var owed: NetworkChange?
    /// When the last network restart came (Self.clock); nil before the first.
    private var lastNetworkRestart: TimeInterval?
    /// The same network back within this (a road tunnel, a dead zone) with its addresses: its
    /// connections are still good, so Xray goes on.
    private static let shortGap: TimeInterval = 15
    /// At most one network restart in this; one more within it waits for its end.
    private static let networkRestartEvery: TimeInterval = 20
    /// Seconds on a clock that a change of the phone's time doesn't move (a step back made the
    /// 20 s wait as long as the step) and that goes on in sleep (a gap then counts in full).
    private static var clock: TimeInterval { TimeInterval(clock_gettime_nsec_np(CLOCK_MONOTONIC)) / 1e9 }
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
    /// The same network back after a short gap (a road tunnel, a dead zone) with the same
    /// addresses is no change: a restart then only cut every flow (YouTube's) for nothing.
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
        let physical = path.availableInterfaces.filter { $0.type != NWInterface.InterfaceType.other && $0.type != NWInterface.InterfaceType.loopback }
        let primary = physical.first
        let before = network
        // The first report is the network we started on (or none yet).
        let first = !reported
        reported = true
        guard path.status == .satisfied, let primary else {
            // Gone (or none yet): since when, to tell a short gap from a new network.
            network = nil
            if lostAt == nil { lostAt = Self.clock }
            return
        }
        let now = Self.key(primary)
        let addresses = Self.addresses(primary.name)
        let last = lastNetwork
        let gap = lostAt.map { Self.clock - $0 }
        network = now
        lastNetwork = (key: now, addresses: addresses)
        available = Set(physical.map(Self.key))
        lostAt = nil
        // The network Xray started on.
        guard !first else { startedOn = lastNetwork; return }
        var change: NetworkChange?
        if let last {
            // Another interface (Wi-Fi ↔ LTE), a long gap, or an address gone: the old
            // connections are dead.
            let moved = last.key == now && Self.changed(last.addresses, addresses)
            if last.key != now || moved || (gap ?? 0) >= Self.shortGap {
                change = NetworkChange(from: last.key, to: now, addressChanged: moved, gap: gap, via: [last.key, now])
            }
        } else {
            // No network since the start (after a reboot iOS starts the VPN before it): what
            // Xray opened meanwhile hangs.
            change = NetworkChange(from: "нет сети", to: now, gap: gap, via: [now])
        }
        // Xray down after a failed restart: no flows to keep, so no waiting out the 20 s.
        let down = failures > 0
        if let change {
            log.info("network: \(change.text, privacy: .public)")
            owed = owed.map { $0.then(change) } ?? change
            // A new network: its tries count from the start.
            failures = 0
            if down { scheduleRestart(after: 1.5) } else { scheduleNetworkRestart() }
        } else if before == nil, down || owed != nil {
            // The same network back, Xray still to start again on it: the next try of a failed
            // restart (now, not after its wait), or a restart decided before the gap.
            if down { scheduleRestart(after: 1.5) } else { scheduleNetworkRestart() }
        }
    }
    private var reported = false

    private static func key(_ interface: NWInterface) -> String {
        "\(interface.type):\(interface.name)"
    }

    /// Why Xray starts again for the network, as the journal says it.
    private struct NetworkChange {
        var from: String
        var to: String
        var addressChanged = false
        var gap: TimeInterval?
        /// The networks it went through ("type:name"): Xray's new connections went out over each.
        var via: Set<String> = []

        /// Another change while this one waits: from the first network to the last one.
        func then(_ next: NetworkChange) -> NetworkChange {
            NetworkChange(from: from, to: next.to, addressChanged: addressChanged || next.addressChanged,
                          gap: [gap, next.gap].compactMap { $0 }.max(), via: via.union(next.via))
        }

        var text: String {
            var s = "перезапуск при смене сети: \(from) → \(to)"
            if addressChanged { s += ", адрес сменился" }
            if let gap { s += ", пауза \(max(1, Int(gap.rounded()))) с" }
            return s
        }
    }

    /// The interface's addresses (by its BSD name), link-local ones left out.
    private static func addresses(_ name: String) -> Set<String> {
        var out = Set<String>()
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return out }
        defer { freeifaddrs(list) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let p = cursor {
            cursor = p.pointee.ifa_next
            guard let addr = p.pointee.ifa_addr, String(cString: p.pointee.ifa_name) == name else { continue }
            switch Int32(addr.pointee.sa_family) {
            case AF_INET:
                let a = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
                if (a >> 16) == 0xA9FE { continue } // 169.254.0.0/16
            case AF_INET6:
                var a = addr.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee.sin6_addr }
                let (b0, b1) = withUnsafeBytes(of: &a) { ($0[0], $0[1]) }
                if b0 == 0xFE && (b1 & 0xC0) == 0x80 { continue } // fe80::/10
            default:
                continue
            }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                out.insert(String(cString: host))
            }
        }
        return out
    }

    /// An address the interface had is gone (or it had none): connections from it are dead.
    /// One more beside them (a new IPv6 temporary address) ends none.
    private static func changed(_ old: Set<String>, _ new: Set<String>) -> Bool {
        old.isEmpty ? !new.isEmpty : !old.isSubset(of: new)
    }

    /// A restart for [change] that waited still has something to do: Xray was started on no
    /// network or another one, an address of it is gone, the network was away long, or a network
    /// it went through meanwhile is gone (LTE → Wi-Fi → LTE, the Wi-Fi left: what Xray opened
    /// over it hangs; in Wi-Fi → LTE → Wi-Fi the LTE is still there and so are its connections).
    private static func needed(_ change: NetworkChange, startedOn run: Net?, now: Net?, available: Set<String>) -> Bool {
        guard let run, let now else { return true }
        return run.key != now.key || changed(run.addresses, now.addresses) || (change.gap ?? 0) >= shortGap
            || change.via.contains { $0 != run.key && !available.contains($0) }
    }

    /// The newest one replaces one still waiting.
    private func scheduleRestart(after delay: TimeInterval) {
        pendingRestart?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.restart() }
        pendingRestart = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    /// A moment for the network to settle first (several reports come at once), and at most
    /// one network restart in 20 s: one sooner waits for the end of them, not dropped.
    private func scheduleNetworkRestart() {
        let since = lastNetworkRestart.map { Self.clock - $0 } ?? .infinity
        // Never longer than the 20 s, whatever the clock read.
        scheduleRestart(after: max(1.5, min(Self.networkRestartEvery, Self.networkRestartEvery - since)))
    }

    /// Xray (and the protocol client) again on the network there is now, or on the same one
    /// to free memory (the watchdog). A failure is tried again a little later; the tunnel ends
    /// only when it keeps failing.
    private func restart(memory: Bool = false) {
        // The watchdog's restart takes the place of one still waiting.
        pendingRestart?.cancel()
        pendingRestart = nil
        guard let run = running, !stopped else { return }
        // A network restart that waited: not once the network Xray runs on is back as it was and
        // the ones it went through meanwhile are still there (Wi-Fi → LTE → Wi-Fi within the wait).
        if !memory, failures == 0, let change = owed, network != nil,
           !Self.needed(change, startedOn: startedOn, now: lastNetwork, available: available) {
            log.info("network: back on \(change.to, privacy: .public), no restart")
            owed = nil
            if reasserting { reasserting = false }
            return
        }
        reasserting = true
        // No network now: its next report restarts it; not a failure.
        guard network != nil else { return }
        // A retry isn't another restart.
        if memory {
            memoryRestarts += 1
            lastMemoryRestart = Self.clock
            holdDecided = false
        } else if failures == 0 {
            networkRestarts += 1
            lastNetworkRestart = Self.clock
            if let change = owed { note(change.text) }
        }
        lastRestart = Self.clock
        // The last restart's "after" still to read, a freeMemory's result: this one's now.
        pendingAfter?.cancel()
        pendingAfter = nil
        freedAt = nil
        let before = (footprint: Self.memoryNow().footprint, go: Self.goRetained())
        _ = Self.invoke("stopXray")
        // Its transports' pooled connections (XHTTP, gRPC, Hysteria): a new Xray never reuses
        // them, and they lived on (gRPC's 30 min, Hysteria's for good), restart after restart.
        _ = TitanInvoke("{\"method\":\"closeTransports\"}")
        // Down until it starts again: a restart after a failed one is always needed.
        startedOn = nil
        stopPlugin()
        // What the old run held goes back to iOS now (libXray's own loop frees once a minute).
        _ = TitanInvoke("{\"method\":\"freeMemory\"}")
        do {
            if let plugin = run.plugin { try startPlugin(plugin) }
            try startXray(run.config)
            failures = 0
            // Up on the network there is now: nothing owed to it.
            owed = nil
            startedOn = lastNetwork
            reasserting = false
            setGoLimit()
            let after = Self.memoryNow().footprint
            log.info("restarted (\(memory ? "memory" : "network", privacy: .public)): \(before.footprint / Self.mib, privacy: .public) -> \(after / Self.mib, privacy: .public) MiB")
            var line: String?
            if memory {
                let text = "перезапуск по памяти: \(Self.mb(before.footprint)) → \(Self.mb(after))"
                note(text)
                line = text
            }
            measureAfter(restartAt: before, line: line)
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
    /// Go first gives back what it can at once; if that isn't enough, Xray (and the protocol
    /// client) start again on the same network, the VPN reasserting a moment: the old run's
    /// connections, pools, buffers and UDP flows go. A restart cuts every connection, so it
    /// comes only if it would free enough: 3 MiB or more above what the last start of Xray left
    /// (read once its first observatory check is done), and memory restarts that don't hold
    /// come further and further apart, except right at the limit. The watchdog never ends the
    /// tunnel (it can't know if Не отключать VPN would bring it back); a kill is left to that.
    private var memoryTimer: DispatchSourceTimer?
    private var pressure: DispatchSourceMemoryPressure?
    private var checkEvery: TimeInterval = 0
    /// Any restart, for the network or memory (Self.clock): a memory one comes at most once in
    /// 30 s (once in 10 s right at the limit). Nil before the first.
    private var lastRestart: TimeInterval?
    /// The last memory restart (Self.clock), and how far apart memory restarts come now: 30 s,
    /// doubled (60, 120, 240 s) each time the footprint comes back over the threshold within
    /// `holdFor` of one, back to 30 s when it stays under that long.
    private var lastMemoryRestart: TimeInterval?
    private var memoryRestartEvery: TimeInterval = PacketTunnelProvider.restartEvery
    /// Whether the last memory restart held is decided: on the first check over the threshold
    /// after it, or once it has been under that `holdFor`.
    private var holdDecided = true
    private var memoryRestarts = 0
    private var networkRestarts = 0
    /// Over the threshold, freeMemory alone brought it under.
    private var memoryFrees = 0
    /// Over the threshold, a restart left out: it wouldn't have freed enough.
    private var memorySkips = 0
    /// What the last start or restart left, and when (Self.clock): right after it, then read
    /// again once the new Xray's probes are done (measureAfter). A memory restart frees at most
    /// what is above it.
    private var lastLeft: (bytes: Int64, at: TimeInterval)?
    /// The "after" read still to come (measureAfter).
    private var pendingAfter: DispatchWorkItem?
    /// freeMemory done over the threshold (Self.clock): checkMemory decides on its result
    /// `settle` later.
    private var freedAt: TimeInterval?
    /// The last freeMemory on memory pressure (Self.clock): once in `refreeAfter` at most.
    private var lastPressureFree: TimeInterval?
    /// The watchdog's last freeMemory before a restart (Self.clock): once in `refreeAfter` at most
    /// (a full GC on this queue).
    private var lastFree: TimeInterval?
    /// Its last one above `busy` or with a restart held back (freeBusy): once in `busyFreeEvery`.
    private var lastBusyFree: TimeInterval?
    /// The journal said restarts wouldn't help, since the last restart.
    private var skipNoted = false
    /// Go's soft limit (titan setMemoryLimit), what isn't Go's, what Go needs, why the limit is
    /// what it is ("fit", "need", "gc", "min"), and when it was last set (Self.clock).
    private var goLimit: Int64 = 0
    private var nonGo: Int64 = 0
    private var goNeed: Int64 = 0
    private var goLimitWhy = ""
    private var goLimitAt: TimeInterval?
    private var peak: Int64 = 0
    private var lastMarker = Date.distantPast
    /// The footprint the marker has (writeMarker).
    private var markedFootprint: Int64 = 0
    private var record = RunRecord()
    /// memory.json read (noteLastRun): nothing writes it before.
    private var recordLoaded = false

    private typealias Memory = (footprint: Int64, limit: Int64, remaining: Int64, available: Int64)
    private static let mib: Int64 = 1 << 20
    /// Above it the checks come every 5 s, not 10, and give Go's free pages back (freeBusy).
    /// Every second before (and the marker with them): nothing is decided between it and 40 MiB,
    /// where iOS's own warning (memory pressure, below) starts the 200 ms checks anyway; the 5 s
    /// are for a warning that doesn't come.
    private static let busy = 35 * mib
    /// Above `busy` (under the line) and with a restart held back, Go's free pages go back at
    /// most this often: libXray's own loop does it once a minute now (the xray-mem patch: a forced
    /// full GC every second, traffic or not, before). Bench (Go 1.27, GOGC 10, 28 MiB live, 3 MiB/s,
    /// the footprint 38-39 MiB: HANDOFF «Батарея на iOS»): the footprint as with every second
    /// (38.9-39.2 against 38.2-39.0 MiB), the GC 0.029-0.030 cores against 0.034-0.037; once in
    /// 10 s left it 1.5-2.5 MiB higher (40.7), over the 40 where the 200 ms checks start.
    private static let busyFreeEvery: TimeInterval = 5
    /// The marker (running.json: the footprint a kill leaves) is written when the footprint has
    /// moved this much from the one it has, at a check, and `markerEvery` after the last one
    /// whatever it is. It came every 0.9 s above 35 MiB before (an atomic file write, mostly of
    /// the same footprint): now a rise toward a kill is in it within a check (200 ms near the
    /// line), a footprint that sits isn't written again for 9 s.
    private static let markerStep = mib
    private static let markerEvery: TimeInterval = 9
    /// The restart line, this far under the limit (42 MiB of 50). One new QUIC stream that stalls
    /// (Hysteria, XHTTP/3: a paused player) holds the 6 MiB of data its handshake gave, +7.5 MiB of
    /// heap (xray-mem internet_mem.go), as long as it stalls: quic-go cancels stalled streams only
    /// past half of the connection's window (8 MiB), never one alone. From just under the line it
    /// leaves the footprint under the kill (49.5); over the line a restart comes. 6 under before
    /// (44): from there the same stream was a kill unless the restart at 47 got in first (2.4 MiB
    /// of data, one 200 ms check at 100 Mbit/s). While a restart is held back (too soon, wouldn't
    /// help) the footprint may stay over the line with Go at its ceiling (43): only the restart at
    /// 47 is in the way then. Go's limit is tied to it (titan memory.go watchdogMargin). 5 under
    /// before that, but the line that counted was 10 under (40 of 50): iOS's own warning at 80% of
    /// the limit, which this source gets as critical pressure (below), was taken for "over".
    private static let lineUnder = 8 * mib
    /// This close to the limit (47 MiB of 50) a restart comes at once and 10 s after the last.
    private static let rightAtUnder = 3 * mib
    /// This close (40 MiB of 50, where iOS's own warning comes) the checks come every 200 ms.
    private static let armedUnder = 10 * mib
    /// A restart that would free less than this isn't worth cutting every connection for (the
    /// ones that did nothing freed 0-3 MB).
    private static let minGain = 3 * mib
    /// What a restart left counts this long: the old run's memory may have gone since.
    private static let leftCountsFor: TimeInterval = 300
    /// freeMemory's result is read this much later.
    private static let settle: TimeInterval = 0.3
    /// The watchdog's freeMemory before a restart comes at most once in this.
    private static let refreeAfter: TimeInterval = 10
    /// At most one restart in this (any), memory restarts at first too.
    private static let restartEvery: TimeInterval = 30
    /// A memory restart that the footprint comes back over the threshold within this didn't
    /// hold: the next comes twice as far apart, up to `maxRestartEvery`; one that it stays
    /// under that long did, and they come 30 s apart again.
    private static let holdFor: TimeInterval = 180
    private static let maxRestartEvery: TimeInterval = 240
    /// A restart's "after" is read once the new Xray's first observatory check is done (till then
    /// its probes' own connections are up, 12 at a time, each closed as its probe ends: 7-10 s
    /// with 85 outbounds), `afterDelay` at the least and `afterMax` at the most (before a memory
    /// restart may come again); the least of `afterReadings` a second apart (apps reconnecting
    /// after it).
    private static let afterDelay: TimeInterval = 5
    private static let afterMax: TimeInterval = 25
    private static let afterReadings = 3

    private func watchMemory() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.setEventHandler { [weak self] in self?.checkMemory() }
        memoryTimer = timer
        checkEvery = 0
        checkMemory()
        timer.resume()
        // Memory pressure: the system's, and iOS's own warning at 80% of the extension's limit
        // (XNU puts it on the footprint's ledger, PHYS_FOOTPRINT_WARNING_LEVEL; it comes each
        // time the footprint rises past 40 MiB of 50), which on iOS a source that asks for
        // .critical gets as critical (memorystatus_warn_process). Neither is the restart line
        // (every restart in the 24.10 journal started at 40.0-41.1 MiB, the "critical within 10
        // MiB" rule): Go's free pages back (once in 10 s at most: libXray's own loop frees once
        // a minute), its limit for what is left, and a check (every 200 ms from 40 MiB on).
        let source = DispatchSource.makeMemoryPressureSource(eventMask: [.warning, .critical], queue: queue)
        source.setEventHandler { [weak self] in
            guard let self, let event = self.pressure?.data else { return }
            self.log.notice("memory pressure: \(event.contains(.critical) ? "critical" : "warning", privacy: .public)")
            if self.lastPressureFree.map({ Self.clock - $0 >= Self.refreeAfter }) ?? true {
                self.lastPressureFree = Self.clock
                self.lastBusyFree = Self.clock
                _ = TitanInvoke("{\"method\":\"freeMemory\"}")
            }
            self.setGoLimit()
            self.checkMemory()
        }
        source.resume()
        pressure = source
    }

    /// Every 10 s, every 5 s above 35 MiB, every 200 ms within 10 MiB of the limit. Within
    /// 8 MiB (42 of 50), when a restart may come and would free enough: freeMemory (once in
    /// 10 s at most), and if it is still over a moment later, the restart; within 3 MiB the
    /// restart at once (it frees Go's memory itself). A restart held back (too soon, wouldn't
    /// help, no network) is decided again on every check. Above 35 MiB (and while a restart is held
    /// back) Go's free pages go back once in 5 s (freeBusy).
    private func checkMemory() {
        guard !stopped, running != nil else { return }
        let m = Self.memoryNow()
        peak = max(peak, m.footprint)
        let armed = m.footprint >= m.limit - Self.armedUnder
        if Date().timeIntervalSince(lastMarker) >= Self.markerEvery || abs(m.footprint - markedFootprint) >= Self.markerStep {
            writeMarker(m.footprint)
        }
        let every: TimeInterval = armed ? 0.2 : m.footprint > Self.busy ? 5 : 10
        if every != checkEvery {
            checkEvery = every
            memoryTimer?.schedule(deadline: .now() + every, repeating: every, leeway: .milliseconds(every < 1 ? 50 : 1000))
        }
        if goLimitAt.map({ Self.clock - $0 >= 10 }) ?? true { setGoLimit(m) }
        let over = m.footprint >= m.limit - Self.lineUnder
        let near = m.footprint >= m.limit - Self.rightAtUnder
        // Over the line a restart's freeMemory comes first (below), or this one if it's held back.
        if m.footprint > Self.busy, !over { freeBusy() }
        // Did the last memory restart hold? By when the footprint first came back over the
        // threshold after it, not by when the next restart came (the pause puts that off).
        if !holdDecided, let at = lastMemoryRestart {
            let held = Self.clock - at
            if held >= Self.holdFor {
                holdDecided = true
                memoryRestartEvery = Self.restartEvery
            } else if over {
                holdDecided = true
                let next = min(memoryRestartEvery * 2, Self.maxRestartEvery)
                if next != memoryRestartEvery {
                    memoryRestartEvery = next
                    note("перезапуск по памяти продержался \(Int(held)) с: следующие не чаще раза в \(Int(next)) с (у самого лимита — через 10 с)")
                }
            }
        }
        if let at = freedAt {
            // freeMemory's result: in a moment (at once right at the limit), then this decides.
            guard Self.clock - at >= Self.settle - 0.05 || near else { return }
            freedAt = nil
            guard over else {
                memoryFrees += 1
                log.notice("memory: \(m.footprint / Self.mib, privacy: .public) MiB after freeMemory, no restart")
                return
            }
        } else {
            guard over else { return }
        }
        // No network to start on: not now. A restart waiting (a network one may wait up to 20 s)
        // is done now instead: it frees as much.
        guard network != nil else { freeBusy(); return }
        // One just now: not again for 30 s, or 10 s right at the limit. Memory restarts that
        // didn't hold come further apart, but never when right at the limit: a kill is worse.
        let since = lastRestart.map { Self.clock - $0 } ?? .infinity
        let sinceMemory = lastMemoryRestart.map { Self.clock - $0 } ?? .infinity
        guard (since >= Self.restartEvery && sinceMemory >= memoryRestartEvery) || (near && since >= 10) else {
            freeBusy()
            return
        }
        // It frees at most what is above what the last start of Xray left.
        let left = lastLeft.flatMap { Self.clock - $0.at < Self.leftCountsFor ? $0.bytes : nil }
        if let left, m.footprint - left < Self.minGain {
            if !skipNoted {
                skipNoted = true
                memorySkips += 1
                note("перезапуск по памяти не поможет: \(Self.mb(m.footprint)), после прошлого запуска Xray было \(Self.mb(left))")
                log.info("memory: \(m.footprint / Self.mib, privacy: .public) MiB, the last restart left \(left / Self.mib, privacy: .public): no restart")
            }
            freeBusy()
            return
        }
        // What Go can give back at once first (unless just done): a restart only if that isn't
        // enough, decided `settle` later. Not right at the limit: restart frees too, and the wait
        // and the GC there could be what kills it.
        if !near, lastFree.map({ Self.clock - $0 >= Self.refreeAfter }) ?? true {
            _ = TitanInvoke("{\"method\":\"freeMemory\"}")
            lastFree = Self.clock
            lastBusyFree = Self.clock
            freedAt = Self.clock
            queue.asyncAfter(deadline: .now() + Self.settle) { [weak self] in self?.checkMemory() }
            return
        }
        log.notice("memory: \(m.footprint / Self.mib, privacy: .public) of \(m.limit / Self.mib, privacy: .public) MiB, restarting")
        restart(memory: true)
    }

    /// Go's free pages back above `busy` under the line, and over it with the restart held back (no
    /// network, too soon, wouldn't help): once in `busyFreeEvery` at most, nothing decided on it
    /// (the next checks decide as they come). libXray's own loop gave them back every second; once
    /// a minute now, so where the footprint matters it is this (at a check that comes anyway).
    private func freeBusy() {
        guard lastBusyFree.map({ Self.clock - $0 >= Self.busyFreeEvery - 0.5 }) ?? true else { return }
        _ = TitanInvoke("{\"method\":\"freeMemory\"}")
        lastBusyFree = Self.clock
    }

    /// What the start or restart just done left (what the next memory restart is measured
    /// against): now, and again once the new Xray's first observatory check is done (5-25 s
    /// later, the least of three readings a second apart). A memory restart's journal line
    /// [line] then gets that value in place of the one right after.
    private func measureAfter(restartAt before: (footprint: Int64, go: Int64?)?, line: String?) {
        pendingAfter?.cancel()
        pendingAfter = nil
        let started = Self.clock
        lastLeft = (Self.memoryNow().footprint, started)
        skipNoted = false
        readAfter(started: started, before: before, line: line, low: nil, readings: 0, in: Self.afterDelay)
    }

    /// One reading of measureAfter's, `delay` from now: the least so far [low] of [readings].
    private func readAfter(started: TimeInterval, before: (footprint: Int64, go: Int64?)?, line: String?,
                           low: Int64?, readings: Int, in delay: TimeInterval) {
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.stopped, self.running != nil else { return }
            self.pendingAfter = nil
            let waited = Self.clock - started
            // Its first check still probing: its probes' connections are still up.
            if low == nil, waited < Self.afterMax, Self.observatoryChecking() {
                self.readAfter(started: started, before: before, line: line, low: nil, readings: 0, in: 1)
                return
            }
            let least = min(low ?? Int64.max, Self.memoryNow().footprint)
            if readings + 1 < Self.afterReadings {
                self.readAfter(started: started, before: before, line: line, low: least, readings: readings + 1, in: 1)
                return
            }
            self.lastLeft = (least, Self.clock)
            guard let before, let line else { return }
            var text = "перезапуск по памяти: \(Self.mb(before.footprint)) → \(Self.mb(least)) через \(Int(waited.rounded())) с"
            if let was = before.go, let now = Self.goRetained() { text += " (Go \(Self.mb(was)) → \(Self.mb(now)))" }
            self.replaceNote(line, with: text)
        }
        pendingAfter = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    /// Go's soft limit for the extension's limit and what isn't Go's now (titan setMemoryLimit:
    /// the limit less the rest (at least 4 MiB) and 11 MiB, raised to what Go needs up to 2 MiB
    /// over the watchdog's line (42 of 50) for the rest as measured (at least 1 MiB): Go at it
    /// puts the footprint over the line, the watchdog decides, not a GC running nonstop under
    /// it; to that while the GC is busy, 24 MiB at least). After each start, since libXray's
    /// first runXray sets its own 30 MiB, then every 10 s and on memory pressure.
    private func setGoLimit(_ now: Memory? = nil) {
        let m = now ?? Self.memoryNow()
        goLimitAt = Self.clock
        let reply = TitanInvoke("{\"method\":\"setMemoryLimit\",\"limit\":\(m.limit),\"footprint\":\(m.footprint)}")
        let obj = (try? JSONSerialization.jsonObject(with: Data(reply.utf8))) as? [String: Any]
        if let limit = (obj?["goLimit"] as? NSNumber)?.int64Value, let rest = (obj?["nonGo"] as? NSNumber)?.int64Value {
            goLimit = limit
            nonGo = rest
            goNeed = (obj?["need"] as? NSNumber)?.int64Value ?? 0
            goLimitWhy = obj?["why"] as? String ?? ""
        }
    }

    /// The new Xray's first observatory check still runs (titan memStats, the xray-mem patch).
    private static func observatoryChecking() -> Bool {
        ((memStats()?["checking"] as? NSNumber)?.intValue ?? 0) > 0
    }

    /// The footprint (what jetsam counts) and the limit: the footprint and what iOS says is left,
    /// or 50 MiB if it doesn't say.
    private static func memoryNow() -> Memory {
        let m = titan_mem_read()
        let left = m.available > 0 ? m.available : m.remaining
        return (m.footprint, left > 0 ? m.footprint + left : 50 * mib, m.remaining, m.available)
    }

    /// The Go runtime's memory (titan memStats).
    private static func memStats() -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: Data(TitanInvoke("{\"method\":\"memStats\"}").utf8))) as? [String: Any]
    }

    /// What Go holds now, as its soft limit counts it (all it mapped less what it gave back).
    private static func goRetained() -> Int64? {
        guard let go = memStats(), let total = (go["total"] as? NSNumber)?.int64Value,
              let released = (go["released"] as? NSNumber)?.int64Value else { return nil }
        return total - released
    }

    /// Технический режим's «Память туннеля».
    private func memoryReport() -> Data? {
        let m = Self.memoryNow()
        var out: [String: Any] = [
            "footprint": m.footprint, "peak": max(peak, m.footprint), "limit": m.limit,
            "remaining": m.remaining, "available": m.available, "go": Self.memStats() ?? [:],
            "goLimit": goLimit, "nonGo": nonGo, "goNeed": goNeed, "goLimitWhy": goLimitWhy,
            "memoryRestarts": memoryRestarts, "networkRestarts": networkRestarts,
            "memoryRestartEvery": memoryRestartEvery,
            "memoryFrees": memoryFrees, "memorySkips": memorySkips,
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
        /// Stops, kills, memory and network restarts, the newest last.
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
        saveRecord()
    }

    /// The newest journal line with [old] gets [new] in its place, its time kept.
    private func replaceNote(_ old: String, with new: String) {
        guard let i = record.log.lastIndex(where: { $0.hasSuffix(" " + old) }) else { return }
        record.log[i] = String(record.log[i].dropLast(old.count)) + new
        saveRecord()
    }

    private func saveRecord() {
        if let data = try? JSONEncoder().encode(record) { try? data.write(to: Self.recordURL, options: .atomic) }
    }

    private func writeMarker(_ footprint: Int64) {
        lastMarker = Date()
        markedFootprint = footprint
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

    private static func error(_ text: String, code: Int = 1) -> NSError {
        NSError(domain: "com.titanvps.ios.tunnel", code: code, userInfo: [NSLocalizedDescriptionKey: text])
    }
}

/// Same as the app's XrayRunConfig.mtu.
let XrayTunnelMTU = 1500
