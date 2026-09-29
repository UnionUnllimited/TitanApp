package com.titanvps.app.core

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import androidx.core.content.IntentCompat
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs in its own process (":ping"): libXray allows only one Xray per process and
 * rejects pingBatch while the VPN core runs, so pinging here works with the VPN on.
 * The ping is a real HTTP request through each server, so every protocol
 * (VLESS, Hysteria2, Trojan, …) is measured the same way.
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
                var anyOk = false
                var firstError: String? = null
                // Small batches so results show up progressively, like in Happ.
                for (chunk in items.chunked(CHUNK)) {
                    val batch = chunk.map { it.getString("json") to it.getString("tag") }
                    // Pass 1 (HTTPS) warms the path and works everywhere; pass 2 (plain HTTP)
                    // is closer to the real latency. Use HTTP when it answered, else HTTPS.
                    val https = runCatching { XrayCore.ping(batch, TIMEOUT_SEC, XrayCore.PING_URL_HTTPS) }
                        .getOrElse { e -> List(batch.size) { -1L to (e.message ?: e.toString()) } }
                    val http = runCatching { XrayCore.ping(batch, TIMEOUT_SEC, XrayCore.PING_URL_HTTP) }
                        .getOrElse { List(batch.size) { -1L to null } }
                    val pings = https.zip(http).map { (s, h) -> if (h.first >= 0) h else s }
                    anyOk = anyOk || pings.any { it.first >= 0 }
                    if (firstError == null) firstError = pings.firstNotNullOfOrNull { it.second }
                    receiver?.send(RESULT_PARTIAL, Bundle().apply {
                        putStringArray(KEY_IDS, chunk.map { it.getString("id") }.toTypedArray())
                        putLongArray(KEY_DELAYS, pings.map { it.first }.toLongArray())
                        putStringArray(KEY_ERRORS, pings.map { it.second.orEmpty() }.toTypedArray())
                    })
                }
                receiver?.send(RESULT_DONE, Bundle().apply {
                    // Nothing answered: pass the first error so the user can see why.
                    if (!anyOk) putString(KEY_ERROR, firstError)
                })
            } catch (e: Exception) {
                receiver?.send(RESULT_DONE, Bundle().apply { putString(KEY_ERROR, e.message ?: e.toString()) })
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
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
        private const val TIMEOUT_SEC = 4
        private const val CHUNK = 5 // libXray pingBatch accepts at most 5 configs per call
    }
}
