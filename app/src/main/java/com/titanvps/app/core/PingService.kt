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
 * Like Happ / v2rayNG: one temporary core with a local SOCKS port per server; through
 * each port we open a connection with a warm-up request and time a second request on
 * the same connection. That is the real round trip through the server, without the
 * one-off handshakes. Falls back to libXray's pingBatch if the core can't start.
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
    private fun pingViaSocks(items: List<JSONObject>, receiver: ResultReceiver?): Boolean {
        val ports = XrayCore.freePorts(items.size)
        val config = PingConfig.build(items.map { PingConfig.Item(it.getString("json"), it.getString("tag")) }, ports)
        XrayCore.runPlain(config)
        val results = LongArray(items.size) { -1L }
        val errors = arrayOfNulls<String>(items.size)
        try {
            val pool = Executors.newFixedThreadPool(minOf(items.size, PARALLEL))
            items.indices.map { i ->
                pool.submit {
                    var r = measure(ports[i], TIMEOUT_SEC)
                    if (r.first < 0) r = measure(ports[i], RETRY_TIMEOUT_SEC)
                    results[i] = r.first
                    errors[i] = r.second
                    if (r.first >= 0) send(receiver, items[i].getString("id"), r.first, null)
                }
            }.forEach { runCatching { it.get() } }
            pool.shutdown()
        } finally {
            XrayCore.stopPlain()
        }
        val failed = items.indices.filter { results[it] < 0 }
        val fallbackOk = if (failed.isNotEmpty()) {
            pingBatchFallback(failed.map { items[it] }, receiver) { idx -> "proxy: ${errors[failed[idx]]}" }
        } else false
        return results.any { it >= 0 } || fallbackOk
    }

    /** Warm-up request, then time a second request over the same (kept-alive) connection. */
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
            val warmStart = System.nanoTime()
            client.newCall(request).execute().use { it.body.bytes() }
            val warm = (System.nanoTime() - warmStart) / 1_000_000
            runCatching {
                val start = System.nanoTime()
                client.newCall(request).execute().use { it.body.bytes() }
                (System.nanoTime() - start) / 1_000_000
            }.getOrDefault(warm).coerceAtLeast(1) to null
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
        private const val PARALLEL = 8
        private const val BATCH_LIMIT = 5 // libXray pingBatch accepts at most 5 configs per call
    }
}
