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
            val result = Bundle()
            try {
                val arr = JSONArray(File(path!!).readText())
                val items = (0 until arr.length()).map { arr.getJSONObject(it) }
                val delays = XrayCore.ping(items.map { it.getString("json") to it.getString("tag") }, TIMEOUT_SEC)
                result.putStringArray(KEY_IDS, items.map { it.getString("id") }.toTypedArray())
                result.putLongArray(KEY_DELAYS, delays.toLongArray())
                receiver?.send(0, result)
            } catch (e: Exception) {
                result.putString(KEY_ERROR, e.message)
                receiver?.send(1, result)
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
        private const val TIMEOUT_SEC = 5
    }
}
