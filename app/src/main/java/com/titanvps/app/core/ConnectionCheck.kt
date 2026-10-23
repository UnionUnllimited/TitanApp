package com.titanvps.app.core

import com.titanvps.app.data.UserAgent
import com.titanvps.app.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Проверить соединение (as in Happ, same as iOS): a request through the running VPN, so it
 * shows whether the connection works right now, how long a fresh HTTPS request takes and
 * which address the sites see. Our app is excluded from its own tunnel, so the requests go
 * into the core through its local proxy (a direct one would measure the phone's network).
 */
object ConnectionCheck {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** [country]: ISO code of the exit (Cloudflare's loc=), upper case. */
        data class Ok(val ms: Long, val ip: String?, val country: String?) : State
        /** [reason]: why, for Технический режим. */
        data class Failed(val reason: String?) : State
    }

    /** One check: Google's generate_204 timed, Cloudflare's trace for the exit address. */
    suspend fun once(): State = withContext(Dispatchers.IO) {
        val lp = VpnStatus.localProxy ?: return@withContext State.Failed("VPN не подключён")
        // A new client each time: a new connection, as a ping measures it.
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", lp.port)))
            .proxyAuthenticator { _, response ->
                // Asked once; a second 407 means the password is wrong: give up.
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(lp.user, lp.password)).build()
            }
            .connectTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        try {
            coroutineScope {
                val exit = async { runCatching { trace(client) } }
                val head = runCatching { head(client) }
                val trace = exit.await().getOrNull()
                val ms = head.getOrNull()
                val result: State = when {
                    ms != null -> State.Ok(ms, trace?.ip, trace?.country)
                    // Google didn't answer but Cloudflare did: the VPN carries traffic (the
                    // panel's routing may send Google another way), so it isn't "no answer".
                    trace != null && trace.ip != null -> State.Ok(trace.ms, trace.ip, trace.country)
                    else -> State.Failed(head.exceptionOrNull()?.let { e -> (e as? BadStatus)?.message ?: PingReasons.short(e) })
                }
                result
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    /** ip= and loc= of a Cloudflare trace (one key=value a line); loc upper case. */
    fun parseTrace(body: String): Pair<String?, String?> {
        var ip: String? = null
        var loc: String? = null
        for (line in body.lineSequence()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val value = line.substring(i + 1).trim().ifEmpty { null } ?: continue
            when (line.substring(0, i).trim()) {
                "ip" -> ip = value
                "loc" -> loc = value.uppercase()
            }
        }
        return ip to loc
    }

    private class Trace(val ms: Long, val ip: String?, val country: String?)

    private class BadStatus(code: Int) : IOException("ответ HTTP $code")

    /** HEAD of generate_204 (204 or any 2xx): its time in ms. */
    private fun head(client: OkHttpClient): Long {
        val request = Request.Builder().url(PROBE_URL).head().header("User-Agent", UserAgent.titan).build()
        val start = System.nanoTime()
        client.newCall(request).execute().use { r ->
            val ms = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            if (!r.isSuccessful) throw BadStatus(r.code)
            return ms
        }
    }

    private fun trace(client: OkHttpClient): Trace {
        val request = Request.Builder().url(TRACE_URL).header("User-Agent", UserAgent.titan).build()
        val start = System.nanoTime()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw BadStatus(r.code)
            val body = r.body.string()
            val ms = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            val (ip, loc) = parseTrace(body)
            return Trace(ms, ip, loc)
        }
    }

    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val TRACE_URL = "https://1.1.1.1/cdn-cgi/trace"
}
