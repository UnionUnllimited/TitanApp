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
    )

    private val requests = Executors.newCachedThreadPool()
    private val listPool: ExecutorService = Executors.newFixedThreadPool(PARALLEL)
    private val singlePool: ExecutorService = Executors.newCachedThreadPool()
    private val recheckPool: ExecutorService = Executors.newFixedThreadPool(RECHECK_PARALLEL)
    private val main = Handler(Looper.getMainLooper())
    private val coreLock = Any()
    private var session: Session? = null
    private val active = java.util.concurrent.atomic.AtomicInteger()
    private val idleStop = Runnable { shutdownIfIdle() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    /** Starts (or reuses) the core with every server of the subscription. */
    private fun ensureSession(servers: List<JSONObject>): Session = synchronized(coreLock) {
        val sigs = servers.associate { it.getString("id") to (it.getString("json") + it.getString("tag")).hashCode() }
        // Reuse the warm core if it already has these servers unchanged (e.g. a subset).
        session?.takeIf { s -> sigs.all { (id, sig) -> s.serverSigs[id] == sig } }?.let { return it }
        stopSession()
        XrayCore.ensurePingDns()

        // Naive / Mieru servers: their client as a local SOCKS, the probe goes through it.
        val plugins = mutableListOf<PluginProcess>()
        val jsonOf = servers.associate { item ->
            val json = item.getString("json")
            val tag = item.getString("tag")
            val ep = Plugins.endpoint(item.optString("name"), json, tag)
            item.getString("id") to (ep?.let {
                runCatching { PluginProcess(this, it).also { p -> p.start(); plugins += p } }
                    .map { p -> Plugins.withLocalSocks(json, tag, p.port) }
                    .getOrDefault(json) // client didn't start: the probe just times out
            } ?: json)
        }

        data class Probe(val json: String, val tag: String)
        val probes = LinkedHashMap<String, Probe>()
        val serverKeys = servers.associate { item ->
            val json = jsonOf.getValue(item.getString("id"))
            item.getString("id") to XrayConfigs.pingTags(json, item.getString("tag")).mapNotNull { tag ->
                val key = XrayConfigs.endpointKey(json, tag) ?: return@mapNotNull null
                if (key !in probes && probes.size < MAX_PROBES) probes[key] = Probe(json, tag)
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
        Session(sigs, keys.zip(ports).toMap(), serverKeys, plugins).also { session = it }
    }

    /**
     * Every server is measured on all members of its balancer; a server's ping is its best
     * member — what the balancer itself would pick. Returns true if anything answered.
     */
    private fun measureTargets(servers: List<JSONObject>, targets: List<String>, single: Boolean, receiver: ResultReceiver?): Boolean {
        val s = ensureSession(servers)
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
                synchronized(lock) {
                    val fresh = ids.filter { started.add(it) }
                    if (fresh.isNotEmpty()) sendStarted(receiver, fresh)
                }
                val r = measureKey(s.ports.getValue(key))
                // In a full run a bad result is often just the crowd (Hysteria/QUIC suffers
                // most on mobile): measure it again alone once the list is done.
                if (!single && (r < 0 || r > RECHECK_ABOVE_MS)) recheck[key] = r else report(key, r)
            }
        }.forEach { runCatching { it.get() } }
        // One attempt each, one at a time; keep the better of the two runs.
        // A few at a time, one attempt each; keep the better of the two runs.
        recheck.toList().map { (key, first) ->
            recheckPool.submit { report(key, bestOf(first, measure(s.ports.getValue(key), RECHECK_TIMEOUT_SEC))) }
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
        private const val RECHECK_ABOVE_MS = 1500L
        private const val KEEP_WARM_MS = 60_000L
        private const val BATCH_LIMIT = 5 // libXray pingBatch accepts at most 5 configs per call
    }
}
