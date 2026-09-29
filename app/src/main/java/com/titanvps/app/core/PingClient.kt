package com.titanvps.app.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import com.titanvps.app.data.Server
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Asks [PingService] (separate process) for real delays through each server. */
object PingClient {

    sealed interface Event {
        /** serverId → ms, -1 = timeout / unreachable. */
        data class Partial(val delays: Map<String, Long>) : Event
        /** Batch finished; [error] is set when nothing answered. */
        data class Done(val error: String?) : Event
    }

    fun ping(context: Context, servers: List<Server>): Flow<Event> = callbackFlow {
        val file = File(context.cacheDir, "ping-${System.nanoTime()}.json")
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("id", it.id).put("json", it.xrayJson).put("tag", it.proxyTag)) }
        file.writeText(arr.toString())

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                if (resultCode == PingService.RESULT_PARTIAL) {
                    val ids = data?.getStringArray(PingService.KEY_IDS) ?: return
                    val delays = data.getLongArray(PingService.KEY_DELAYS) ?: return
                    trySend(Event.Partial(ids.zip(delays.toList()).toMap()))
                } else {
                    trySend(Event.Done(data?.getString(PingService.KEY_ERROR)))
                    close()
                }
            }
        }
        context.startService(
            Intent(context, PingService::class.java)
                .putExtra(PingService.EXTRA_FILE, file.absolutePath)
                .putExtra(PingService.EXTRA_RECEIVER, receiver)
        )
        awaitClose { file.delete() }
    }
}
