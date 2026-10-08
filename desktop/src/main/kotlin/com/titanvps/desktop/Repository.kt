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
        // "Titan" is our own UA; "Xray" if the server doesn't answer it with Xray JSON.
        for (ua in Config.USER_AGENTS) for (candidate in listOf(url) + Links.alternates(url)) {
            try {
                val (info, body) = download(candidate, ua)
                val servers = runCatching { XrayConfigs.serversFromXrayJson(body) }.getOrDefault(emptyList())
                    .filterNot(ServerGroups::isHidden)
                if (servers.isEmpty()) {
                    if (ua != Config.USER_AGENTS.last()) continue
                    throw SubscriptionException("В подписке нет серверов")
                }
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

    data class State(val subscription: Subscription?, val selectedId: String?, val theme: String)

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
                    .map { Server(it.getString("id"), it.getString("name"), it.getString("json"), it.getString("tag")) },
                fetchedAt = s.optLong("fetchedAt"),
            )
        }
        return State(sub, o.optString("selected").ifEmpty { null }, o.optString("theme", "system"))
    }

    fun save(state: State) {
        val o = JSONObject().put("theme", state.theme).put("selected", state.selectedId ?: "")
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
 * while the list is still being measured). A few nodes at a time; results arrive one by one.
 */
object Pinger {
    private const val URL = "https://www.gstatic.com/generate_204"
    private const val PARALLEL = 6 // more at once crowd each other out on a weak network
    private const val RECHECK_ABOVE_MS = 1500L
    private const val KEEP_WARM_MS = 60_000L

    private class Session(val sigs: Map<String, Int>, val ports: Map<String, Int>, val serverKeys: Map<String, List<String>>)

    private val core = XrayProcess("ping")
    private val listPool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL) { r -> Thread(r).apply { isDaemon = true } }
    private val singlePool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val recheckPool = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r).apply { isDaemon = true } }
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r).apply { isDaemon = true } }
    private val active = java.util.concurrent.atomic.AtomicInteger()
    private var idleStop: java.util.concurrent.ScheduledFuture<*>? = null
    private var session: Session? = null

    /**
     * Measures [targets] in that order ([all] = every server, for the shared core).
     * [onStart] when a server starts being measured, [onResult] with ms or -1 (timeout).
     */
    fun ping(all: List<Server>, targets: List<String>, onStart: (List<String>) -> Unit, onResult: (String, Long) -> Unit) {
        synchronized(this) { idleStop?.cancel(false); idleStop = null }
        active.incrementAndGet()
        try {
            measure(ensureSession(all), targets, targets.size == 1, onStart, onResult)
        } finally {
            if (active.decrementAndGet() == 0) synchronized(this) {
                idleStop = timer.schedule(Runnable { stopIfIdle() }, KEEP_WARM_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    fun stop() = synchronized(this) { core.stop(); session = null }

    private fun stopIfIdle() = synchronized(this) { if (active.get() == 0) { core.stop(); session = null } }

    @Synchronized
    private fun ensureSession(servers: List<Server>): Session {
        val sigs = servers.associate { it.id to (it.xrayJson + it.proxyTag).hashCode() }
        session?.takeIf { s -> core.isRunning && sigs.all { (id, sig) -> s.sigs[id] == sig } }?.let { return it }
        data class Probe(val json: String, val tag: String)
        val probes = LinkedHashMap<String, Probe>()
        val serverKeys = servers.associate { s ->
            s.id to XrayConfigs.pingTags(s.xrayJson, s.proxyTag).mapNotNull { tag ->
                val key = XrayConfigs.endpointKey(s.xrayJson, tag) ?: return@mapNotNull null
                if (key !in probes && probes.size < 200) probes[key] = Probe(s.xrayJson, tag)
                key.takeIf { it in probes }
            }.distinct()
        }
        val keys = probes.keys.toList()
        val ports = if (keys.isEmpty()) emptyList() else freePorts(keys.size)
        if (keys.isNotEmpty()) core.start(XrayConfigs.buildPingConfig(keys.map { probes.getValue(it).let { p -> p.json to p.tag } }, ports))
        return Session(sigs, keys.zip(ports).toMap(), serverKeys).also { session = it }
    }

    private fun measure(s: Session, targets: List<String>, single: Boolean, onStart: (List<String>) -> Unit, onResult: (String, Long) -> Unit) {
        val best = HashMap<String, Long>()
        val remaining = HashMap<String, Int>()
        val started = HashSet<String>()
        val byKey = LinkedHashMap<String, MutableList<String>>()
        targets.forEach { id ->
            val keys = s.serverKeys[id].orEmpty()
            remaining[id] = keys.size
            keys.forEach { byKey.getOrPut(it) { mutableListOf() }.add(id) }
        }
        targets.filter { remaining[it] == 0 }.forEach { onResult(it, -1) }
        val lock = Any()
        fun report(key: String, ms: Long) = synchronized(lock) {
            for (id in byKey.getValue(key)) {
                if (ms >= 0 && (best[id] ?: -1L).let { it < 0 || ms < it }) best[id] = ms
                remaining[id] = remaining.getValue(id) - 1
                if (remaining[id] == 0) onResult(id, best[id] ?: -1L)
            }
        }
        val recheck = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())
        val pool = if (single) singlePool else listPool
        byKey.map { (key, ids) ->
            pool.submit {
                synchronized(lock) { ids.filter { started.add(it) }.takeIf { it.isNotEmpty() }?.let(onStart) }
                val port = s.ports.getValue(key)
                // One cold attempt (a dead server costs 4 s); if it answered, a second one
                // and the better of the two.
                var ms = timed(port, 4)
                if (ms >= 0) ms = bestOf(ms, timed(port, 3))
                // In a full run a bad result is often just the crowd (Hysteria/QUIC suffers
                // most): measure it again alone once the list is done.
                if (!single && (ms < 0 || ms > RECHECK_ABOVE_MS)) recheck[key] = ms else report(key, ms)
            }
        }.forEach { runCatching { it.get() } }
        // A few at a time, one attempt each; keep the better of the two runs.
        recheck.toList().map { (key, first) ->
            recheckPool.submit { report(key, bestOf(first, timed(s.ports.getValue(key), 6))) }
        }.forEach { runCatching { it.get() } }
    }

    private fun bestOf(a: Long, b: Long): Long = when {
        a < 0 -> b
        b < 0 -> a
        else -> minOf(a, b)
    }

    /** One request on a fresh connection (TCP, TLS and the proxy handshake included). */
    private fun timed(port: Int, timeoutSec: Long): Long {
        val client = OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(timeoutSec, TimeUnit.SECONDS) // the whole attempt, not per phase
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
}
