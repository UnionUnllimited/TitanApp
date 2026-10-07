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
    private class Session(val serverSigs: Map<String, Int>, val ports: Map<String, Int>, val serverKeys: Map<String, List<String>>)

    private val requests = Executors.newCachedThreadPool()
    private val listPool: ExecutorService = Executors.newFixedThreadPool(PARALLEL)
    private val singlePool: ExecutorService = Executors.newCachedThreadPool()
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
        if (session != null) XrayCore.stopPlain()
        session = null
        XrayCore.ensurePingDns()

        data class Probe(val json: String, val tag: String)
        val probes = LinkedHashMap<String, Probe>()
        val serverKeys = servers.associate { item ->
            val json = item.getString("json")
            item.getString("id") to XrayConfigs.pingTags(json, item.getString("tag")).mapNotNull { tag ->
                val key = XrayConfigs.endpointKey(json, tag) ?: return@mapNotNull null
                if (key !in probes && probes.size < MAX_PROBES) probes[key] = Probe(json, tag)
                key.takeIf { it in probes }
            }.distinct()
        }
        val keys = probes.keys.toList()
        val ports = XrayCore.freePorts(keys.size)
        XrayCore.runPlain(PingConfig.build(keys.map { probes.getValue(it).let { p -> PingConfig.Item(p.json, p.tag) } }, ports))
        Session(sigs, keys.zip(ports).toMap(), serverKeys).also { session = it }
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
        // Keys go into the pool in the order of the servers asked for, so the list fills top-down.
        serversByKey.map { (key, ids) ->
            pool.submit {
                synchronized(lock) {
                    val fresh = ids.filter { started.add(it) }
                    if (fresh.isNotEmpty()) sendStarted(receiver, fresh)
                }
                val port = s.ports.getValue(key)
                // Best of two cold attempts: one slow handshake on a weak network
                // shouldn't decide the number.
                var r = bestOf(measure(port, TIMEOUT_SEC), measure(port, TIMEOUT_SEC))
                if (r < 0) r = measure(port, RETRY_TIMEOUT_SEC)
                synchronized(lock) {
                    for (id in ids) {
                        if (r >= 0 && (best[id] ?: -1L).let { it < 0 || r < it }) best[id] = r
                        remaining[id] = remaining.getValue(id) - 1
                        if (remaining[id] == 0) send(receiver, id, best[id] ?: -1L)
                    }
                }
            }
        }.forEach { runCatching { it.get() } }
        return best.isNotEmpty()
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
            .callTimeout(timeoutSec + 2L, TimeUnit.SECONDS)
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

    private fun shutdownIfIdle() {
        synchronized(coreLock) {
            if (active.get() > 0) return
            if (session != null) XrayCore.stopPlain()
            session = null
        }
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(idleStop)
        synchronized(coreLock) {
            if (session != null) runCatching { XrayCore.stopPlain() }
            session = null
        }
        requests.shutdownNow()
        listPool.shutdownNow()
        singlePool.shutdownNow()
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
        private const val TIMEOUT_SEC = 5
        private const val RETRY_TIMEOUT_SEC = 10
        private const val PARALLEL = 6 // more at once crowd each other out on a weak mobile network
        private const val MAX_PROBES = 200
        private const val KEEP_WARM_MS = 60_000L
        private const val BATCH_LIMIT = 5 // libXray pingBatch accepts at most 5 configs per call
    }
}
