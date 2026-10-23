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
        /** These servers are being measured right now. */
        data class Started(val ids: List<String>) : Event
        /** serverId → ms (-1 = timeout / unreachable); [errors]: why a -1 got no answer. */
        data class Partial(val delays: Map<String, Long>, val errors: Map<String, String> = emptyMap()) : Event
        /** Batch finished; [error] is set when nothing answered. */
        data class Done(val error: String?) : Event
    }

    /**
     * Measures [targets] in that order. [all] is every server of the subscription: the
     * service keeps one warm core for all of them, shared by concurrent requests.
     */
    /** Stops the measurements in progress (what is still queued isn't measured). */
    fun cancel(context: Context) {
        runCatching { context.startService(Intent(context, PingService::class.java).putExtra(PingService.EXTRA_CANCEL, true)) }
    }

    fun ping(context: Context, all: List<Server>, targets: List<String>): Flow<Event> = callbackFlow {
        val file = File(context.cacheDir, "ping-${System.nanoTime()}.json")
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("json", it.xrayJson).put("tag", it.proxyTag)) }
        file.writeText(JSONObject().put("servers", arr).put("targets", JSONArray(targets)).toString())

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, data: Bundle?) {
                if (resultCode == PingService.RESULT_STARTED) {
                    val ids = data?.getStringArray(PingService.KEY_IDS) ?: return
                    trySend(Event.Started(ids.toList()))
                } else if (resultCode == PingService.RESULT_PARTIAL) {
                    val ids = data?.getStringArray(PingService.KEY_IDS) ?: return
                    val delays = data.getLongArray(PingService.KEY_DELAYS) ?: return
                    // Aligned with the ids; "" where there's no reason.
                    val reasons = data.getStringArray(PingService.KEY_ERRORS)
                    val errors: Map<String, String> = if (reasons == null) emptyMap() else {
                        ids.indices.mapNotNull { i -> reasons.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { ids[i] to it } }.toMap()
                    }
                    trySend(Event.Partial(ids.zip(delays.toList()).toMap(), errors))
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
