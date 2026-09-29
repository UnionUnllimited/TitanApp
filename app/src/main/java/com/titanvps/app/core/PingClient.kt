package com.titanvps.app.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import com.titanvps.app.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/** Asks [PingService] (separate process) for real delays. Result: serverId → ms, -1 = unreachable. */
object PingClient {

    suspend fun ping(context: Context, servers: List<Server>): Map<String, Long> {
        if (servers.isEmpty()) return emptyMap()
        val failed = servers.associate { it.id to -1L }
        val file = File(context.cacheDir, "ping-${System.nanoTime()}.json")
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            servers.forEach { arr.put(JSONObject().put("id", it.id).put("json", it.xrayJson).put("tag", it.proxyTag)) }
            file.writeText(arr.toString())
        }
        try {
            return withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine { cont ->
                    val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                            val ids = data?.getStringArray(PingService.KEY_IDS)
                            val delays = data?.getLongArray(PingService.KEY_DELAYS)
                            val result = if (resultCode == 0 && ids != null && delays != null) {
                                failed + ids.zip(delays.toList()).toMap()
                            } else failed
                            if (cont.isActive) cont.resume(result)
                        }
                    }
                    context.startService(
                        Intent(context, PingService::class.java)
                            .putExtra(PingService.EXTRA_FILE, file.absolutePath)
                            .putExtra(PingService.EXTRA_RECEIVER, receiver)
                    )
                }
            } ?: failed
        } finally {
            file.delete()
        }
    }
}
