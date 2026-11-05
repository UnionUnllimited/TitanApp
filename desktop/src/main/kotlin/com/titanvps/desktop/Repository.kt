package com.titanvps.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads the subscription (api1 ↔ api2 fallback) and keeps app state on disk. */
object Repository {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Never through our own system proxy.
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    private val stateFile = File(AppPaths.dataDir, "state.json")

    fun fetch(url: String): Subscription {
        if (!Links.isAllowed(url)) throw SubscriptionException("Недопустимый адрес подписки")
        var firstError: Exception? = null
        // Our own UA ("Titan(Windows/…)"); "Xray" if the server doesn't answer it with Xray JSON.
        for (ua in Config.USER_AGENTS) for (candidate in listOf(url) + Links.alternates(url)) {
            try {
                val (info, body) = download(candidate, ua)
                val servers = runCatching { XrayConfigs.serversFromXrayJson(body) }.getOrDefault(emptyList())
                    .filterNot(ServerGroups::isHidden)
                if (servers.isEmpty()) {
                    if (ua != Config.USER_AGENTS.last()) continue
                    throw SubscriptionException(SubscriptionErrors.unusable(info, body))
                }
                // The panel answers with a stub entry instead of servers (device limit, blocked…).
                SubscriptionErrors.stub(servers.map { it.name })?.let { throw SubscriptionException(it) }
                return Subscription(url, info, servers, System.currentTimeMillis())
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        throw (firstError as? SubscriptionException) ?: SubscriptionException("Сервер подписки временно недоступен. Попробуйте позже")
    }

    private fun download(url: String, userAgent: String): Pair<SubscriptionInfo, String> {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .header("x-hwid", Hwid.get())
            .header("x-device-os", "Windows")
            .header("x-ver-os", System.getProperty("os.version") ?: "")
            .header("x-device-model", Hwid.model())
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!Links.isAllowed(resp.request.url.toString())) throw SubscriptionException("Недопустимый адрес подписки")
                when {
                    resp.code == 404 -> throw SubscriptionException("Подписка не найдена или отключена")
                    !resp.isSuccessful -> throw SubscriptionException("Сервер подписки временно недоступен. Попробуйте позже")
                }
                SubscriptionHeaders.parse { resp.header(it) } to resp.body.string()
            }
        } catch (e: IOException) {
            throw SubscriptionException("Нет соединения с сервером подписки. Проверьте интернет")
        }
    }

    // ------------------------------------------------------------------ state

    data class State(
        val subscription: Subscription?,
        val selectedId: String?,
        val theme: String,
        /** "proxy" (system proxy) or "tun". */
        val mode: String = "proxy",
        /** Exe names that bypass the VPN in TUN mode. */
        val excludedApps: Set<String> = emptySet(),
        /** Connect as soon as the app starts. */
        val autoConnect: Boolean = false,
        /** "exclude": [excludedApps] bypass the VPN; "only": just [vpnApps] go through it. */
        val appsMode: String = "exclude",
        val vpnApps: Set<String> = emptySet(),
        /** The hidden «Технический режим» was unlocked by its code (its row stays visible). */
        val techMode: Boolean = false,
        /** Starred servers (by name, so they survive a subscription update): shown first. */
        val favorites: Set<String> = emptySet(),
        /** Напоминания: the subscription ends soon, the bypass traffic runs out. */
        val reminders: Boolean = true,
        /** Reminders already shown ("<expire>:<days>", "traffic:<total>"), each once. */
        val reminded: Set<String> = emptySet(),
    )

    fun load(): State {
        val o = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: return State(null, null, "system")
        val sub = o.optJSONObject("subscription")?.let { s ->
            val i = s.getJSONObject("info")
            val arr = s.getJSONArray("servers")
            Subscription(
                url = s.getString("url"),
                info = SubscriptionInfo(
                    i.optLong("upload"), i.optLong("download"), i.optLong("total"), i.optLong("expire"),
                    i.optString("supportUrl").ifEmpty { null }, i.optString("webPageUrl").ifEmpty { null },
                    i.optString("announce").ifEmpty { null },
                ),
                servers = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { Server(it.getString("id"), it.getString("name"), it.getString("json"), it.getString("tag")) }
                    // Saved before a hiding rule existed: apply it now, not only on refresh.
                    .filterNot(ServerGroups::isHidden),
                fetchedAt = s.optLong("fetchedAt"),
            )
        }
        fun names(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        return State(
            sub, o.optString("selected").ifEmpty { null }, o.optString("theme", "system"), o.optString("mode", "proxy"),
            names("excludedApps"), o.optBoolean("autoConnect", false), o.optString("appsMode", "exclude"), names("vpnApps"),
            o.optBoolean("techMode", false), names("favorites"), o.optBoolean("reminders", true), names("reminded"),
        )
    }

    fun save(state: State) {
        val o = JSONObject().put("theme", state.theme).put("selected", state.selectedId ?: "")
            .put("mode", state.mode).put("excludedApps", JSONArray(state.excludedApps.sorted()))
            .put("autoConnect", state.autoConnect)
            .put("appsMode", state.appsMode).put("vpnApps", JSONArray(state.vpnApps.sorted()))
            .put("techMode", state.techMode)
            .put("favorites", JSONArray(state.favorites.sorted())).put("reminders", state.reminders)
            .put("reminded", JSONArray(state.reminded.sorted()))
        state.subscription?.let { s ->
            o.put("subscription", JSONObject()
                .put("url", s.url).put("fetchedAt", s.fetchedAt)
                .put("info", JSONObject()
                    .put("upload", s.info.uploadBytes).put("download", s.info.downloadBytes)
                    .put("total", s.info.totalBytes).put("expire", s.info.expireAt)
                    .put("supportUrl", s.info.supportUrl ?: "").put("webPageUrl", s.info.webPageUrl ?: "")
                    .put("announce", s.info.announce ?: ""))
                .put("servers", JSONArray().apply {
                    s.servers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("json", it.xrayJson).put("tag", it.proxyTag)) }
                }))
        }
        val tmp = File(stateFile.parentFile, "state.json.tmp")
        tmp.writeText(o.toString())
        stateFile.delete()
        tmp.renameTo(stateFile)
    }
}

/**
 * Real ping through Xray, like Happ: one core with a SOCKS port per node is started once
 * and kept warm for a minute, shared by every request (the whole list or one server, also
 * while the list is still being measured). A few nodes at a time, one probe at a time per
 * host a node dials first; results arrive one by one, a list's misses and slow answers once
 * they were measured again at the end ([PingRun], the same rules as Android).
 */
val Pinger = PingEngine("ping")

class PingEngine(private val name: String) {
    private companion object {
        const val URL = "https://www.gstatic.com/generate_204"
        const val PARALLEL = 6 // more at once crowd each other out on a weak network
        const val KEEP_WARM_MS = 60_000L
        const val MAX_PROBES = 200
        /** Balancer members measured per server: in a list its ping is the best of them. */
        const val MAX_MEMBERS = 30
    }

    private class Session(
        val sigs: Map<String, Int>,
        val ports: Map<String, Int>,
        val serverKeys: Map<String, List<String>>,
        val plugins: List<PluginProcess> = emptyList(),
        /** Probes over UDP (Hysteria, TUIC, MASQUE over H3): counted apart in a list's line in ping.log. */
        val udp: Set<String> = emptySet(),
        /** Probes through a protocol client still starting: measured once it is up. */
        val ready: Map<String, java.util.concurrent.Future<*>> = emptyMap(),
        /** The host each probe dials first: one probe at a time per host ([PingRun.run]). */
        val hosts: Map<String, String> = emptyMap(),
    )

    private val core = XrayProcess(name)
    private val listPool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL) { r -> Thread(r).apply { isDaemon = true } }
    private val singlePool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val recheckPool = java.util.concurrent.Executors.newFixedThreadPool(PingRun.RECHECK_PARALLEL) { r -> Thread(r).apply { isDaemon = true } }
    private val startPool = java.util.concurrent.Executors.newFixedThreadPool(8) { r -> Thread(r).apply { isDaemon = true } }
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r).apply { isDaemon = true } }
    private val active = java.util.concurrent.atomic.AtomicInteger()
    private var idleStop: java.util.concurrent.ScheduledFuture<*>? = null
    private var session: Session? = null
    /** Bumped by [cancel]: measurements started before it stop taking new probes. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    /**
     * Measurements hold it shared; a new core (other servers) waits for them: restarting
     * the core under a running measurement made it a timeout (ping one, then the next).
     */
    private val sessionLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /** Stops the measurements in progress: what is still queued isn't measured. */
    fun cancel() { generation.incrementAndGet() }

    /**
     * Measures [targets] in that order ([all] = every server, for the shared core).
     * [onStart] when a server starts being measured, [onResult] with ms or -1 (timeout).
     * [maxMembers]: balancer members measured per server (null: [MAX_MEMBERS]); [recheck]:
     * false, a list's misses and slow answers aren't measured again at the end (only UDP's
     * get a second attempt at once). Android's whitelist watcher asks for both; nothing on
     * Windows does yet (Главная's Пинг and a row's ping use the defaults).
     */
    fun ping(
        all: List<Server>,
        targets: List<String>,
        onStart: (List<String>) -> Unit,
        onResult: (String, Long) -> Unit,
        maxMembers: Int? = null,
        recheck: Boolean = true,
    ) {
        synchronized(this) { idleStop?.cancel(false); idleStop = null }
        val gen = generation.get()
        val startedAt = System.nanoTime()
        active.incrementAndGet()
        try {
            val s = acquireSession(all, targets)
            try {
                val single = targets.size == 1
                val run = measure(s, targets, single, PingRun.maxMembers(maxMembers, MAX_MEMBERS), recheck, onStart, onResult) {
                    generation.get() != gen
                }
                // A list's line in Технический режим: how much its first pass missed, how much the recheck found.
                if (!single && recheck) note(run.line((System.nanoTime() - startedAt) / 1_000_000))
            } finally {
                sessionLock.readLock().unlock()
            }
        } finally {
            if (active.decrementAndGet() == 0) synchronized(this) {
                idleStop = timer.schedule(Runnable { stopIfIdle() }, KEEP_WARM_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    fun stop() = synchronized(this) { stopSession() }

    private fun stopIfIdle() = synchronized(this) { if (active.get() == 0) stopSession() }

    private fun stopSession() {
        core.stop()
        session?.plugins?.forEach { runCatching { it.close() } }
        session = null
    }

    private fun sig(s: Server) = (s.xrayJson + s.proxyTag).hashCode()

    /** The warm core, if it already has the servers asked for (unchanged). */
    @Synchronized
    private fun covering(all: List<Server>, targets: List<String>): Session? =
        session?.takeIf { s -> core.isRunning && all.filter { it.id in targets }.all { s.sigs[it.id] == sig(it) } }

    /** A session with [targets], returned with the read lock held (the caller unlocks). */
    private fun acquireSession(all: List<Server>, targets: List<String>): Session {
        sessionLock.readLock().lock()
        covering(all, targets)?.let { return it }
        sessionLock.readLock().unlock()
        sessionLock.writeLock().lock()
        try {
            val s = covering(all, targets) ?: ensureSession(all, targets)
            sessionLock.readLock().lock() // downgrade: measure under the read lock
            return s
        } finally {
            sessionLock.writeLock().unlock()
        }
    }

    @Synchronized
    private fun ensureSession(all: List<Server>, targets: List<String>): Session {
        // Cold: one server asked for → a core for it and those the old core had (pinging one
        // server after another doesn't restart it each time; starting every Naive / sing-box
        // client of the subscription first would take a minute); a list → all of them.
        val had = session?.sigs?.keys.orEmpty()
        val servers = if (targets.size == 1) all.filter { it.id in targets || it.id in had } else all
        val sigs = servers.associate { it.id to sig(it) }
        session?.plugins?.forEach { runCatching { it.close() } }
        // Naive / Mieru / sing-box servers: their client as a local SOCKS, the probe goes
        // through it; MASQUE: rewritten for Xray itself. The clients start side by side in
        // the background (each takes a second or more: antivirus scan) while the core is
        // already measuring the rest; a probe through a client waits for that client only.
        val plugins = java.util.Collections.synchronizedList(mutableListOf<PluginProcess>())
        val startOf = HashMap<String, java.util.concurrent.Future<*>>()
        val jsonOf = servers.associate { s ->
            val (json, p) = runCatching { Plugins.prepare(s, start = false) }.getOrDefault(s.xrayJson to null)
            if (p != null) {
                plugins += p
                startOf[s.id] = startPool.submit { p.start() }
            }
            s.id to json
        }
        val ready = HashMap<String, java.util.concurrent.Future<*>>()
        data class Probe(val json: String, val tag: String)
        val probes = LinkedHashMap<String, Probe>()
        val udp = HashSet<String>()
        val hosts = HashMap<String, String>()
        val serverKeys = servers.associate { s ->
            val json = jsonOf.getValue(s.id)
            val endpoint = Plugins.endpoint(s)
            // TUIC (sing-box client) and MASQUE over H3 run over UDP whatever the outbound says.
            val serverUdp = endpoint?.let { it.kind == Plugins.Kind.TUIC || (it.kind == Plugins.Kind.MASQUE && !it.http2) } == true
            // Its proxy outbound replaced by the protocol's client (or Xray's MASQUE): that dials the real server.
            val client = endpoint?.takeIf { json != s.xrayJson }
            s.id to XrayConfigs.pingTags(json, s.proxyTag, MAX_MEMBERS).mapNotNull { tag ->
                val key = XrayConfigs.endpointKey(json, tag) ?: return@mapNotNull null
                if (key !in probes && probes.size < MAX_PROBES) probes[key] = Probe(json, tag)
                if (key in probes && (serverUdp || isUdp(json, tag))) udp += key
                startOf[s.id]?.let { if (key in probes) ready[key] = it }
                // From the server's own config: through a protocol client the probe dials that
                // client's server, not the local SOCKS [json] points at (loopback: no rule).
                if (key in probes && key !in hosts) XrayConfigs.entryHost(s, tag, client)?.let { hosts[key] = it }
                key.takeIf { it in probes }
            }.distinct()
        }
        val keys = probes.keys.toList()
        val ports = if (keys.isEmpty()) emptyList() else freePorts(keys.size)
        if (keys.isNotEmpty()) core.start(XrayConfigs.buildPingConfig(keys.map { probes.getValue(it).let { p -> p.json to p.tag } }, ports))
        return Session(sigs, keys.zip(ports).toMap(), serverKeys, plugins, udp, ready, hosts).also { session = it }
    }

    /** Hysteria, QUIC, mKCP… outbounds: they go over UDP. */
    private fun isUdp(json: String, tag: String): Boolean {
        val arr = runCatching { org.json.JSONObject(json).optJSONArray("outbounds") }.getOrNull() ?: return false
        val ob = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("tag") == tag } ?: return false
        val protocol = ob.optString("protocol").lowercase()
        val network = ob.optJSONObject("streamSettings")?.optString("network").orEmpty().lowercase()
        return protocol in setOf("hysteria", "hysteria2", "tuic", "wireguard") || network in setOf("hysteria", "quic", "kcp", "mkcp")
    }

    /**
     * Every server is measured on up to [maxMembers] members of its balancer; in a list a
     * server's ping is its best member, what the balancer itself would pick; one server asked
     * for, its first member that answers ([PingRun.run]). [maxMembers] limits only what this
     * request measures: the core keeps every member warm.
     */
    private fun measure(
        s: Session, targets: List<String>, single: Boolean, maxMembers: Int, recheck: Boolean,
        onStart: (List<String>) -> Unit, onResult: (String, Long) -> Unit, cancelled: () -> Boolean,
    ): PingRun.Summary = PingRun.run(
        targets = targets,
        keysOf = { s.serverKeys[it].orEmpty() },
        single = single,
        pool = if (single) singlePool else listPool,
        recheckPool = recheckPool,
        measure = { key, timeoutSec, _ -> timed(s.ports.getValue(key), timeoutSec) },
        // The rows show the ms (or «таймаут»), not why: the reason stays in PingRun.
        result = { id, ms, _ -> onResult(id, ms) },
        started = onStart,
        ready = { s.ready[it] },
        udp = s.udp,
        cancelled = cancelled,
        maxMembers = maxMembers,
        recheck = recheck,
        // A single request has no limit of its own (its pool grows), but one per host too: a
        // balancer's members behind one entry are the same burst as a list's. It is shown at
        // its first member that answers, so one at a time doesn't add up every member's time.
        hosts = s.hosts,
        parallel = if (single) Int.MAX_VALUE else PARALLEL,
    )

    /** One request on a fresh connection (TCP, TLS and the proxy handshake included). */
    private fun timed(port: Int, timeoutSec: Int): Long {
        val client = OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS) // the whole attempt, not per phase
            .retryOnConnectionFailure(false)
            .build()
        return try {
            val start = System.nanoTime()
            client.newCall(Request.Builder().url(URL).build()).execute().use { it.body.bytes() }
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        } catch (e: Exception) {
            -1
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    /**
     * One line in ping.log (Технический режим → Журнал), no server names or addresses; the
     * file starts over past 64 KB.
     */
    private fun note(line: String) {
        runCatching {
            val f = File(AppPaths.logDir, "$name.log")
            if (f.length() > 64 * 1024) f.writeText("")
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line\n")
        }
    }
}
