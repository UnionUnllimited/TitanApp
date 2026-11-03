package com.titanvps.app.core

import com.titanvps.app.data.UserAgent
import com.titanvps.app.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Проверить соединение (as in Happ, same as iOS): requests through the running VPN, so it
 * shows whether the connection works right now, its latency and which address the sites
 * see. Our app is excluded from its own tunnel, so the requests go into the core through its
 * local proxy (a direct one would measure the phone's network).
 *
 * The latency is measured warm: the first request opens the connection (the proxy's
 * handshake with the server, DNS, Google's TLS), which takes a fresh connection's time, not
 * the latency, so its time is never shown. The next ones on the same connection are one
 * round trip each: phone → server → Google and back.
 */
object ConnectionCheck {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /**
         * [ms]: the warm latency, null when it couldn't be measured (shown without a number).
         * [country]: ISO code of the exit (Cloudflare's loc=), upper case.
         * [sampled]: the latency samples went (a number or not).
         */
        data class Ok(val ms: Long?, val ip: String?, val country: String?, val sampled: Boolean = false) : State
        /** [reason]: why, for Технический режим. */
        data class Failed(val reason: String?) : State
    }

    /** How a latency is shown: green, plain, or a word instead of the number. */
    enum class Latency { FAST, NORMAL, SLOW }

    fun latencyOf(ms: Long): Latency = when {
        ms < FAST_MS -> Latency.FAST
        ms < SLOW_MS -> Latency.NORMAL
        else -> Latency.SLOW
    }

    /**
     * The middle one; of an even count the upper of the two (as iOS: a slow answer isn't hidden
     * by a quick one). Null without any.
     */
    fun median(samples: List<Long>): Long? {
        if (samples.isEmpty()) return null
        return samples.sorted()[samples.size / 2]
    }

    /**
     * One check: Google's generate_204 (it works, then its latency), Cloudflare's trace for
     * the exit address. [warm] false: the latency isn't measured (null).
     */
    suspend fun once(warm: Boolean = true): State = withContext(Dispatchers.IO) {
        val lp = VpnStatus.localProxy ?: return@withContext State.Failed("VPN не подключён")
        val opened = Opened()
        // A new client each time: its own connections, opened by the first requests and
        // reused by the samples (keep-alive or HTTP/2).
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", lp.port)))
            .proxyAuthenticator { _, response ->
                // Asked once; a second 407 means the password is wrong: give up.
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(lp.user, lp.password)).build()
            }
            .eventListener(opened)
            .connectTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        try {
            coroutineScope {
                val exit = async { runCatching { trace(client) } }
                val head = runCatching { head(client) }
                val trace = exit.await().getOrNull()
                val result: State = when {
                    // Sampled once the trace is done: nothing else of ours on the line.
                    head.isSuccess -> State.Ok(if (warm) latency(client, opened) else null, trace?.ip, trace?.country, sampled = warm)
                    // Google didn't answer but Cloudflare did: the VPN carries traffic (the
                    // panel's routing may send Google another way), so it isn't "no answer".
                    trace != null && trace.ip != null -> State.Ok(null, trace.ip, trace.country)
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

    private class Trace(val ip: String?, val country: String?)

    private class BadStatus(code: Int) : IOException("ответ HTTP $code")

    /** Counts the connections the client starts: a sample that needed one isn't warm. */
    private class Opened : EventListener() {
        val count = AtomicInteger()

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            count.incrementAndGet()
        }
    }

    /**
     * Up to [SAMPLES] more HEADs on the connection the first one opened, one after another, as
     * iOS: one without an answer within [SAMPLE_TIMEOUT_MS] counts as that long (slow) and ends
     * the measurement, as does one that failed or needed a new connection (the warm one is
     * gone). Their [median]; null without any (quietly, no number). A few hundred bytes each.
     */
    private fun latency(client: OkHttpClient, opened: Opened): Long? {
        val samples = ArrayList<Long>(SAMPLES)
        while (samples.size < SAMPLES) {
            val before = opened.count.get()
            val ms = try {
                head(client, SAMPLE_TIMEOUT_MS)
            } catch (e: InterruptedIOException) {
                // No answer in time: slow, not a quick one left out.
                samples += SAMPLE_TIMEOUT_MS
                break
            } catch (e: Exception) {
                break
            }
            if (opened.count.get() != before) break
            samples += ms
        }
        return median(samples)
    }

    /** HEAD of generate_204 (204 or any 2xx): its time in ms. [timeoutMs]: else the client's. */
    private fun head(client: OkHttpClient, timeoutMs: Long? = null): Long {
        val request = Request.Builder().url(PROBE_URL).head().header("User-Agent", UserAgent.titan).build()
        val call = client.newCall(request)
        timeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        val start = System.nanoTime()
        call.execute().use { r ->
            val ms = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            if (!r.isSuccessful) throw BadStatus(r.code)
            return ms
        }
    }

    private fun trace(client: OkHttpClient): Trace {
        val request = Request.Builder().url(TRACE_URL).header("User-Agent", UserAgent.titan).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw BadStatus(r.code)
            val (ip, loc) = parseTrace(r.body.string())
            return Trace(ip, loc)
        }
    }

    /** Under it: green. */
    const val FAST_MS = 120L
    /** From it: a word, not the number. */
    const val SLOW_MS = 300L

    /** Requests timed after the one that opens the connection. */
    private const val SAMPLES = 3
    /** Each timed request's wait; one without an answer by then counts as this long. */
    private const val SAMPLE_TIMEOUT_MS = 1_500L

    /** Google's: routed through the server by the panel (Russian sites go direct). */
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val TRACE_URL = "https://1.1.1.1/cdn-cgi/trace"
}
