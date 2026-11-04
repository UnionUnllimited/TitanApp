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
import java.util.concurrent.atomic.AtomicInteger

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
     * Pings under way (Главная's, the bypass watcher's): the VPN notification's probe and the
     * watcher's next look wait. A request counts until its flow is closed, and collecting it
     * returns only after that, so a caller's own finished ping never counts here.
     */
    private val running = AtomicInteger()

    val busy: Boolean get() = running.get() > 0

    /** Stops the measurements in progress (what is still queued isn't measured). */
    fun cancel(context: Context) {
        runCatching { context.startService(Intent(context, PingService::class.java).putExtra(PingService.EXTRA_CANCEL, true)) }
    }

    /**
     * Measures [targets] in that order. [all] is every server of the subscription: the
     * service keeps one warm core for all of them, shared by concurrent requests.
     * [maxMembers]: balancer members measured per server (null: the service's own limit).
     * [recheck]: false, a list's misses and slow answers aren't measured again at the end
     * (the watcher: who answers is all it needs, and it can't wait behind Главная's rechecks).
     */
    fun ping(
        context: Context,
        all: List<Server>,
        targets: List<String>,
        maxMembers: Int? = null,
        recheck: Boolean = true,
    ): Flow<Event> = callbackFlow {
        val file = File(context.cacheDir, "ping-${System.nanoTime()}.json")
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("json", it.xrayJson).put("tag", it.proxyTag)) }
        val request = JSONObject().put("servers", arr).put("targets", JSONArray(targets))
        maxMembers?.let { request.put("maxMembers", it) }
        if (!recheck) request.put("recheck", false)
        file.writeText(request.toString())

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
        running.incrementAndGet()
        awaitClose {
            running.decrementAndGet()
            file.delete()
        }
    }
}
