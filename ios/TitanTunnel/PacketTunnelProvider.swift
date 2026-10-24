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
                    self.setGoLimit()
                    self.measureAfter(restartAt: nil, line: nil)
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
            pendingAfter?.cancel()
            pendingAfter = nil
            running = nil
            _ = Self.invoke("stopXray")
            // Its pooled connections closed now, not when the process goes.
            _ = TitanInvoke("{\"method\":\"closeTransports\"}")
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
        // What the old run held goes back to iOS now (libXray frees once a second anyway).
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
    /// doubled (to 4 min) each time one comes within `holdFor` of the one before.
    private var lastMemoryRestart: TimeInterval?
    private var memoryRestartEvery: TimeInterval = PacketTunnelProvider.restartEvery
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
    /// `settle` later. Critical pressure meanwhile counts then.
    private var freedAt: TimeInterval?
    private var freeCritical = false
    /// The watchdog's last freeMemory before a restart (Self.clock): once in `refreeAfter` at
    /// most (a full GC on this queue; libXray frees every second anyway).
    private var lastFree: TimeInterval?
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
    private var record = RunRecord()
    /// memory.json read (noteLastRun): nothing writes it before.
    private var recordLoaded = false

    private typealias Memory = (footprint: Int64, limit: Int64, remaining: Int64, available: Int64)
    private static let mib: Int64 = 1 << 20
    /// Above it the checks and the marker come every second.
    private static let busy = 35 * mib
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
    /// hold: the next comes twice as far apart, up to `maxRestartEvery`.
    private static let holdFor: TimeInterval = 180
    private static let maxRestartEvery: TimeInterval = 240
    /// A restart's "after" is read once the new Xray's first observatory check is done (its
    /// probes add connections to the pools till then: 7-10 s with 85 outbounds), `afterDelay`
    /// at the least and `afterMax` at the most (before a memory restart may come again); the
    /// least of `afterReadings` a second apart (apps reconnecting after it).
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
        // System-wide pressure: Go's free pages back at once, its limit for what is left, and a
        // restart sooner on critical.
        let source = DispatchSource.makeMemoryPressureSource(eventMask: [.warning, .critical], queue: queue)
        source.setEventHandler { [weak self] in
            guard let self, let event = self.pressure?.data else { return }
            self.log.notice("memory pressure: \(event.contains(.critical) ? "critical" : "warning", privacy: .public)")
            _ = TitanInvoke("{\"method\":\"freeMemory\"}")
            self.setGoLimit()
            self.checkMemory(critical: event.contains(.critical))
        }
        source.resume()
        pressure = source
    }

    /// Every 10 s, every second above 35 MiB, every 200 ms within 10 MiB of the limit. Within
    /// 5 MiB (or within 10 on critical pressure), when a restart may come and would free
    /// enough: freeMemory (once in 10 s at most), and if it is still over a moment later, the
    /// restart. A restart held back (too soon, wouldn't help, no network) is decided again on
    /// every check, without freeing again.
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
        if goLimitAt.map({ Self.clock - $0 >= 10 }) ?? true { setGoLimit(m) }
        let over = m.footprint >= m.limit - 5 * Self.mib || ((critical || freeCritical) && armed)
        if let at = freedAt {
            // freeMemory's result: in a moment, then this decides.
            guard Self.clock - at >= Self.settle - 0.05 else {
                freeCritical = freeCritical || critical
                return
            }
            freedAt = nil
            freeCritical = false
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
        guard network != nil else { return }
        // One just now: not again for 30 s, or 10 s right at the limit. Memory restarts that
        // didn't hold come further apart, but never when right at the limit: a kill is worse.
        let near = m.footprint >= m.limit - 2 * Self.mib
        let since = lastRestart.map { Self.clock - $0 } ?? .infinity
        let sinceMemory = lastMemoryRestart.map { Self.clock - $0 } ?? .infinity
        if sinceMemory >= Self.holdFor { memoryRestartEvery = Self.restartEvery }
        guard (since >= Self.restartEvery && sinceMemory >= memoryRestartEvery) || (near && since >= 10) else { return }
        // It frees at most what is above what the last start of Xray left.
        let left = lastLeft.flatMap { Self.clock - $0.at < Self.leftCountsFor ? $0.bytes : nil }
        if let left, m.footprint - left < Self.minGain {
            if !skipNoted {
                skipNoted = true
                memorySkips += 1
                note("перезапуск по памяти не поможет: \(Self.mb(m.footprint)), после прошлого запуска Xray было \(Self.mb(left))")
                log.info("memory: \(m.footprint / Self.mib, privacy: .public) MiB, the last restart left \(left / Self.mib, privacy: .public): no restart")
            }
            return
        }
        // What Go can give back at once first (unless just done): a restart only if that isn't
        // enough, decided `settle` later.
        if lastFree.map({ Self.clock - $0 >= Self.refreeAfter }) ?? true {
            _ = TitanInvoke("{\"method\":\"freeMemory\"}")
            lastFree = Self.clock
            freedAt = Self.clock
            freeCritical = critical
            queue.asyncAfter(deadline: .now() + Self.settle) { [weak self] in self?.checkMemory() }
            return
        }
        // Over again so soon after the last memory restart: the next ones come further apart.
        if sinceMemory < Self.holdFor {
            let next = min(memoryRestartEvery * 2, Self.maxRestartEvery)
            if next != memoryRestartEvery {
                memoryRestartEvery = next
                note("перезапуск по памяти продержался \(Int(sinceMemory)) с: следующие не чаще раза в \(Int(next)) с (у самого лимита — через 10 с)")
            }
        }
        log.notice("memory: \(m.footprint / Self.mib, privacy: .public) of \(m.limit / Self.mib, privacy: .public) MiB, restarting")
        restart(memory: true)
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
            // Its first check still probing: the pools aren't all there yet.
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
    /// the limit less the rest and 8 MiB, raised to what Go needs up to the watchdog's
    /// threshold, to it while the GC is busy, 24 MiB at least). After each start, since
    /// libXray's first runXray sets its own 30 MiB, then every 10 s and on memory pressure.
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
