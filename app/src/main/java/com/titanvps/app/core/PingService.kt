package com.titanvps.app.core

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import androidx.core.content.IntentCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pings every server through Xray, in its own process (":ping") so it works while the
 * VPN core runs (libXray allows one core per process).
 *
 * Like Happ / v2RayTun: one temporary core with a local SOCKS port per server; through
 * each port we time one request on a fresh connection (handshakes included). Falls back
 * to libXray's pingBatch if the core can't start.
 */
class PingService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val receiver = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RECEIVER, ResultReceiver::class.java) }
        val path = intent?.getStringExtra(EXTRA_FILE)
        executor.execute {
            try {
                val arr = JSONArray(File(path!!).readText())
                val items = (0 until arr.length()).map { arr.getJSONObject(it) }
                XrayCore.ensurePingDns()
                val anyOk = runCatching { pingViaSocks(items, receiver) }
                    .getOrElse { pingBatchFallback(items, receiver) }
                receiver?.send(RESULT_DONE, Bundle().apply {
                    if (!anyOk) putString(KEY_ERROR, "серверы не ответили")
                })
            } catch (e: Exception) {
                receiver?.send(RESULT_DONE, Bundle().apply { putString(KEY_ERROR, e.message ?: e.toString()) })
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Returns true if anything answered. Servers that fail get one retry with a longer
     * timeout, then libXray's pingBatch as a last resort, so a slow first handshake
     * isn't reported as a timeout.
     */
    /**
     * Every server is measured on all members of its balancer; identical nodes shared
     * between configs (a country and АВТО) are pinged once. A server's ping is its best
     * member — what the balancer itself would pick.
     */
    private fun pingViaSocks(items: List<JSONObject>, receiver: ResultReceiver?): Boolean {
        data class Probe(val json: String, val tag: String)
        val probes = LinkedHashMap<String, Probe>()           // endpoint key -> first config using it
        val serverKeys = items.map { item ->
            val json = item.getString("json")
            XrayConfigs.pingTags(json, item.getString("tag")).mapNotNull { tag ->
                val key = XrayConfigs.endpointKey(json, tag) ?: return@mapNotNull null
                if (key !in probes && probes.size < MAX_PROBES) probes[key] = Probe(json, tag)
                key.takeIf { it in probes }
            }.distinct()
        }
        val keys = probes.keys.toList()
        val ports = XrayCore.freePorts(keys.size)
        XrayCore.runPlain(PingConfig.build(keys.map { probes.getValue(it).let { p -> PingConfig.Item(p.json, p.tag) } }, ports))

        val result = HashMap<String, Long>()
        val best = LongArray(items.size) { -1L }
        val remaining = IntArray(items.size) { serverKeys[it].size }
        val serversByKey = HashMap<String, MutableList<Int>>().also { m ->
            serverKeys.forEachIndexed { i, ks -> ks.forEach { m.getOrPut(it) { mutableListOf() }.add(i) } }
        }
        val lock = Any()
        try {
            val pool = Executors.newFixedThreadPool(minOf(keys.size.coerceAtLeast(1), PARALLEL))
            keys.mapIndexed { p, key ->
                pool.submit {
                    // Best of two cold attempts: one slow handshake on a weak network
                    // shouldn't decide the number.
                    var r = bestOf(measure(ports[p], TIMEOUT_SEC), measure(ports[p], TIMEOUT_SEC))
                    if (r.first < 0) r = measure(ports[p], RETRY_TIMEOUT_SEC)
                    synchronized(lock) {
                        result[key] = r.first
                        for (i in serversByKey[key].orEmpty()) {
                            if (r.first >= 0 && (best[i] < 0 || r.first < best[i])) best[i] = r.first
                            remaining[i]--
                            // Report a server as soon as all its members are measured.
                            if (remaining[i] == 0 && best[i] >= 0) send(receiver, items[i].getString("id"), best[i], null)
                        }
                    }
                }
            }.forEach { runCatching { it.get() } }
            pool.shutdown()
        } finally {
            XrayCore.stopPlain()
        }
        val failed = items.indices.filter { best[it] < 0 }
        val fallbackOk = if (failed.isNotEmpty()) pingBatchFallback(failed.map { items[it] }, receiver) else false
        return best.any { it >= 0 } || fallbackOk
    }

    private fun bestOf(a: Pair<Long, String?>, b: Pair<Long, String?>): Pair<Long, String?> = when {
        a.first < 0 -> b
        b.first < 0 -> a
        else -> if (a.first <= b.first) a else b
    }

    /** Time of one request through the server on a fresh connection. */
    private fun measure(port: Int, timeoutSec: Int): Pair<Long, String?> {
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
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1) to null
        } catch (e: Exception) {
            -1L to (e.message ?: e.javaClass.simpleName)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun pingBatchFallback(
        items: List<JSONObject>,
        receiver: ResultReceiver?,
        previousError: (Int) -> String? = { null },
    ): Boolean {
        var anyOk = false
        items.chunked(BATCH_LIMIT).forEachIndexed { chunkIndex, chunk ->
            val pings = runCatching {
                XrayCore.ping(chunk.map { it.getString("json") to it.getString("tag") }, RETRY_TIMEOUT_SEC)
            }.getOrElse { e -> List(chunk.size) { -1L to (e.message ?: e.toString()) } }
            chunk.zip(pings).forEachIndexed { i, (item, p) ->
                val error = if (p.first >= 0) null
                else listOfNotNull(previousError(chunkIndex * BATCH_LIMIT + i), p.second?.let { "core: $it" }).joinToString("\n")
                send(receiver, item.getString("id"), p.first, error)
            }
            anyOk = anyOk || pings.any { it.first >= 0 }
        }
        return anyOk
    }

    private fun send(receiver: ResultReceiver?, id: String, delay: Long, error: String?) {
        receiver?.send(RESULT_PARTIAL, Bundle().apply {
            putStringArray(KEY_IDS, arrayOf(id))
            putLongArray(KEY_DELAYS, longArrayOf(delay))
            putStringArray(KEY_ERRORS, arrayOf(error.orEmpty()))
        })
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_RECEIVER = "receiver"
        const val KEY_IDS = "ids"
        const val KEY_DELAYS = "delays"
        const val KEY_ERROR = "error"
        const val KEY_ERRORS = "errors"
        const val RESULT_PARTIAL = 1
        const val RESULT_DONE = 0
        private const val PING_URL = "https://www.gstatic.com/generate_204"
        private const val TIMEOUT_SEC = 5
        private const val RETRY_TIMEOUT_SEC = 10
        private const val PARALLEL = 6 // more at once crowd each other out on a weak mobile network
        private const val MAX_PROBES = 200
        private const val BATCH_LIMIT = 5 // libXray pingBatch accepts at most 5 configs per call
    }
}
