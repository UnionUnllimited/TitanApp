package com.titanvps.app.core

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.content.IntentCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pings servers through Xray, in its own process (":ping") so it works while the VPN
 * core runs (libXray allows one core per process).
 *
 * Like Happ: one core with a local SOCKS port per server endpoint is started once and
 * kept warm for a minute, so every request (the whole list or a single server, even
 * while the list is still being measured) starts measuring right away. Servers are
 * measured in the order asked, a few at a time, and each result is reported as soon as
 * it's ready. Falls back to libXray's pingBatch if the core can't start.
 */
class PingService : Service() {

    /** The running ping core: endpoint key → local port, server id → its endpoint keys. */
    private class Session(
        val serverSigs: Map<String, Int>,
        val ports: Map<String, Int>,
        val serverKeys: Map<String, List<String>>,
        /** Naive / Mieru clients for the servers that need one. */
        val plugins: List<PluginProcess> = emptyList(),
        /** Probes over UDP (Hysteria, TUIC, MASQUE over H3): the only ones measured twice. */
        val udp: Set<String> = emptySet(),
        /** Probes through a protocol client still starting: measured once it is up. */
        val ready: Map<String, java.util.concurrent.Future<*>> = emptyMap(),
    )

    private val requests = Executors.newCachedThreadPool()
    private val listPool: ExecutorService = Executors.newFixedThreadPool(PARALLEL)
    private val singlePool: ExecutorService = Executors.newCachedThreadPool()
    private val recheckPool: ExecutorService = Executors.newFixedThreadPool(RECHECK_PARALLEL)
    private val startPool: ExecutorService = Executors.newFixedThreadPool(8)
    private val main = Handler(Looper.getMainLooper())
    private val coreLock = Any()
    private var session: Session? = null
    private val active = java.util.concurrent.atomic.AtomicInteger()
    private val idleStop = Runnable { shutdownIfIdle() }
    /** Bumped by a cancel request: measurements started before it stop taking new probes. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_CANCEL, false) == true) {
            generation.incrementAndGet()
            // Started just for this (nothing running): don't stay around.
            if (active.get() == 0) { main.removeCallbacks(idleStop); main.postDelayed(idleStop, KEEP_WARM_MS) }
            return START_NOT_STICKY
        }
        val receiver = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RECEIVER, ResultReceiver::class.java) }
        val path = intent?.getStringExtra(EXTRA_FILE)
        main.removeCallbacks(idleStop)
        active.incrementAndGet()
        requests.execute {
            try {
                val root = JSONObject(File(path!!).readText())
                val servers = root.getJSONArray("servers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                val targets = root.getJSONArray("targets").let { a -> (0 until a.length()).map { a.getString(it) } }
                val single = targets.size == 1
                val anyOk = runCatching { measureTargets(servers, targets, single, receiver) }
                    .getOrElse { pingBatchFallback(servers.filter { it.getString("id") in targets }, receiver) }
                receiver?.send(RESULT_DONE, Bundle().apply {
                    if (!anyOk) putString(KEY_ERROR, "серверы не ответили")
                })
            } catch (e: Exception) {
                receiver?.send(RESULT_DONE, Bundle().apply { putString(KEY_ERROR, e.message ?: e.toString()) })
            } finally {
                if (active.decrementAndGet() == 0) main.postDelayed(idleStop, KEEP_WARM_MS)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Measurements hold it shared; a new core (other servers) waits for them: restarting
     * the core under a running measurement made it a timeout (ping one, then the next).
     */
    private val sessionLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    private fun sig(it: JSONObject) = (it.getString("json") + it.getString("tag")).hashCode()

    /** The warm core, if it already has the servers asked for (unchanged). */
    private fun covering(all: List<JSONObject>, targets: List<String>): Session? = synchronized(coreLock) {
        session?.takeIf { s -> all.filter { it.getString("id") in targets }.all { s.serverSigs[it.getString("id")] == sig(it) } }
    }

    /** A session with [targets], returned with the read lock held (the caller unlocks). */
    private fun acquireSession(all: List<JSONObject>, targets: List<String>): Session {
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

    /** Starts the core with the servers asked for. */
    private fun ensureSession(all: List<JSONObject>, targets: List<String>): Session = synchronized(coreLock) {
        // Cold: one server asked for → a core for it and those the old core had (pinging one
        // server after another doesn't restart it each time; starting every Naive / sing-box
        // client of the subscription first takes long); a list → all of them.
        val had = session?.serverSigs?.keys.orEmpty()
        val servers = if (targets.size == 1) all.filter { it.getString("id") in targets || it.getString("id") in had } else all
        val sigs = servers.associate { it.getString("id") to sig(it) }
        stopSession()
        XrayCore.ensurePingDns()

        // Naive / Mieru / sing-box servers: their client as a local SOCKS, the probe goes
        // through it; MASQUE: rewritten for Xray itself. The clients start side by side in the
        // background while the core is already measuring the rest; a probe through a client
        // waits for that client only.
        val plugins = java.util.Collections.synchronizedList(mutableListOf<PluginProcess>())
        val startOf = HashMap<String, java.util.concurrent.Future<*>>()
        val jsonOf = servers.associate { item ->
            val json = item.getString("json")
            val (prepared, p) = runCatching { Plugins.prepare(this, item.optString("name"), json, item.getString("tag"), start = false) }
                .getOrDefault(json to null)
            if (p != null) {
                plugins += p
                startOf[item.getString("id")] = startPool.submit { p.start() }
            }
            item.getString("id") to prepared
        }
        val ready = HashMap<String, java.util.concurrent.Future<*>>()

        data class Probe(val json: String, val tag: String)
        val probes = LinkedHashMap<String, Probe>()
        val udp = HashSet<String>()
        val serverKeys = servers.associate { item ->
            val json = jsonOf.getValue(item.getString("id"))
            // TUIC (sing-box client) and MASQUE over H3 run over UDP whatever the outbound says.
            val serverUdp = Plugins.endpoint(item.optString("name"), item.getString("json"), item.getString("tag"))
                ?.let { it.kind == Plugins.Kind.TUIC || (it.kind == Plugins.Kind.MASQUE && !it.http2) } == true
            item.getString("id") to XrayConfigs.pingTags(json, item.getString("tag"), MAX_MEMBERS).mapNotNull { tag ->
                val key = XrayConfigs.endpointKey(json, tag) ?: return@mapNotNull null
                if (key !in probes && probes.size < MAX_PROBES) probes[key] = Probe(json, tag)
                if (key in probes && (serverUdp || isUdp(json, tag))) udp += key
                startOf[item.getString("id")]?.let { if (key in probes) ready[key] = it }
                key.takeIf { it in probes }
            }.distinct()
        }
        val keys = probes.keys.toList()
        val ports = XrayCore.freePorts(keys.size)
        try {
            XrayCore.runPlain(PingConfig.build(keys.map { probes.getValue(it).let { p -> PingConfig.Item(p.json, p.tag) } }, ports))
        } catch (e: Exception) {
            plugins.forEach { runCatching { it.close() } }
            throw e
        }
        Session(sigs, keys.zip(ports).toMap(), serverKeys, plugins, udp, ready).also { session = it }
    }

    /**
     * Every server is measured on all members of its balancer; a server's ping is its best
     * member — what the balancer itself would pick. Returns true if anything answered.
     */
    private fun measureTargets(servers: List<JSONObject>, targets: List<String>, single: Boolean, receiver: ResultReceiver?): Boolean {
        val gen = generation.get()
        val cancelled = { generation.get() != gen }
        val s = acquireSession(servers, targets)
        try {
            return measureWith(s, targets, single, receiver, cancelled)
        } finally {
            sessionLock.readLock().unlock()
        }
    }

    private fun measureWith(s: Session, targets: List<String>, single: Boolean, receiver: ResultReceiver?, cancelled: () -> Boolean): Boolean {
        val pool = if (single) singlePool else listPool
        val keysOf = targets.associateWith { s.serverKeys[it].orEmpty() }
        val best = HashMap<String, Long>()
        val remaining = HashMap<String, Int>()
        val started = HashSet<String>()
        val serversByKey = LinkedHashMap<String, MutableList<String>>()
        targets.forEach { id ->
            remaining[id] = keysOf.getValue(id).size
            keysOf.getValue(id).forEach { serversByKey.getOrPut(it) { mutableListOf() }.add(id) }
        }
        targets.filter { remaining[it] == 0 }.forEach { send(receiver, it, -1L) }
        val lock = Any()
        fun report(key: String, r: Long) = synchronized(lock) {
            for (id in serversByKey.getValue(key)) {
                if (r >= 0 && (best[id] ?: -1L).let { it < 0 || r < it }) best[id] = r
                remaining[id] = remaining.getValue(id) - 1
                if (remaining[id] == 0) send(receiver, id, best[id] ?: -1L)
            }
        }
        // Keys go into the pool in the order of the servers asked for, so the list fills top-down.
        val recheck = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())
        serversByKey.map { (key, ids) ->
            pool.submit {
                if (cancelled()) return@submit
                synchronized(lock) {
                    val fresh = ids.filter { started.add(it) }
                    if (fresh.isNotEmpty()) sendStarted(receiver, fresh)
                }
                // Through a protocol client: once it is up (it didn't start: no answer).
                s.ready[key]?.let { start ->
                    if (runCatching { start.get(30, java.util.concurrent.TimeUnit.SECONDS) }.isFailure) { report(key, -1L); return@submit }
                }
                val r = measureKey(s.ports.getValue(key))
                // In a full run a bad result over UDP (Hysteria, TUIC) is often just the crowd:
                // measured again alone once the list is done. TCP results stand as they are.
                if (!single && key in s.udp && (r < 0 || r > RECHECK_ABOVE_MS)) recheck[key] = r else report(key, r)
            }
        }.forEach { runCatching { it.get() } }
        if (cancelled()) return best.isNotEmpty()
        // A few at a time, one attempt each; keep the better of the two runs. Not needed when
        // every server using it already has a good result (e.g. another balancer member).
        val (needed, settled) = recheck.toList().partition { (key, _) ->
            synchronized(lock) { serversByKey.getValue(key).any { (best[it] ?: -1L) < 0 } }
        }
        settled.forEach { (key, first) -> report(key, first) }
        needed.map { (key, first) ->
            recheckPool.submit { if (!cancelled()) report(key, bestOf(first, measure(s.ports.getValue(key), RECHECK_TIMEOUT_SEC))) }
        }.forEach { runCatching { it.get() } }
        return best.isNotEmpty()
    }

    /**
     * One cold attempt; if it answered, a second one (the first handshake is often the
     * slow part) and the better of the two. A dead server costs [TIMEOUT_SEC] here.
     */
    private fun measureKey(port: Int): Long {
        val first = measure(port, TIMEOUT_SEC)
        return if (first < 0) first else bestOf(first, measure(port, SECOND_TIMEOUT_SEC))
    }

    /** Hysteria, QUIC, mKCP… outbounds: they go over UDP. */
    private fun isUdp(json: String, tag: String): Boolean {
        val arr = runCatching { JSONObject(json).optJSONArray("outbounds") }.getOrNull() ?: return false
        val ob = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("tag") == tag } ?: return false
        val protocol = ob.optString("protocol").lowercase()
        val network = ob.optJSONObject("streamSettings")?.optString("network").orEmpty().lowercase()
        return protocol in setOf("hysteria", "hysteria2", "tuic", "wireguard") || network in setOf("hysteria", "quic", "kcp", "mkcp")
    }

    private fun bestOf(a: Long, b: Long): Long = when {
        a < 0 -> b
        b < 0 -> a
        else -> minOf(a, b)
    }

    /** Time of one request through the server on a fresh connection, or -1. */
    private fun measure(port: Int, timeoutSec: Int): Long {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS) // the whole attempt, not per phase
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder().url(PING_URL).build()
        return try {
            // Like Happ / v2RayTun: one request on a fresh connection, including TCP,
            // TLS and the proxy handshake.
            val start = System.nanoTime()
            client.newCall(request).execute().use { it.body.bytes() }
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        } catch (e: Exception) {
            -1L
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun pingBatchFallback(items: List<JSONObject>, receiver: ResultReceiver?): Boolean {
        sendStarted(receiver, items.map { it.getString("id") })
        var anyOk = false
        items.chunked(BATCH_LIMIT).forEach { chunk ->
            val pings = runCatching {
                XrayCore.ping(chunk.map { it.getString("json") to it.getString("tag") }, RETRY_TIMEOUT_SEC)
            }.getOrElse { List(chunk.size) { -1L to null } }
            chunk.zip(pings).forEach { (item, p) -> send(receiver, item.getString("id"), p.first) }
            anyOk = anyOk || pings.any { it.first >= 0 }
        }
        return anyOk
    }

    private fun send(receiver: ResultReceiver?, id: String, delay: Long) {
        receiver?.send(RESULT_PARTIAL, Bundle().apply {
            putStringArray(KEY_IDS, arrayOf(id))
            putLongArray(KEY_DELAYS, longArrayOf(delay))
        })
    }

    private fun sendStarted(receiver: ResultReceiver?, ids: List<String>) {
        receiver?.send(RESULT_STARTED, Bundle().apply { putStringArray(KEY_IDS, ids.toTypedArray()) })
    }

    /** Stops the ping core and its Naive / Mieru clients (call under [coreLock]). */
    private fun stopSession() {
        session?.let { s ->
            XrayCore.stopPlain()
            s.plugins.forEach { runCatching { it.close() } }
        }
        session = null
    }

    private fun shutdownIfIdle() {
        synchronized(coreLock) {
            if (active.get() > 0) return
            stopSession()
        }
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(idleStop)
        synchronized(coreLock) { runCatching { stopSession() } }
        requests.shutdownNow()
        listPool.shutdownNow()
        singlePool.shutdownNow()
        recheckPool.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_CANCEL = "cancel"
        const val KEY_IDS = "ids"
        const val KEY_DELAYS = "delays"
        const val KEY_ERROR = "error"
        const val RESULT_PARTIAL = 1
        const val RESULT_STARTED = 2
        const val RESULT_DONE = 0
        private const val PING_URL = "https://www.gstatic.com/generate_204"
        private const val TIMEOUT_SEC = 4
        private const val SECOND_TIMEOUT_SEC = 3
        private const val RECHECK_TIMEOUT_SEC = 6
        private const val RECHECK_PARALLEL = 3
        private const val RETRY_TIMEOUT_SEC = 10 // pingBatch fallback
        private const val PARALLEL = 6 // more at once crowd each other out on a weak mobile network
        private const val MAX_PROBES = 200
        /** Balancer members measured per server: its ping is the best of them. */
        private const val MAX_MEMBERS = 30
        private const val RECHECK_ABOVE_MS = 1500L
        private const val KEEP_WARM_MS = 60_000L
        private const val BATCH_LIMIT = 5 // libXray pingBatch accepts at most 5 configs per call
    }
}
