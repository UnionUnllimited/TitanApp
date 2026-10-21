package com.titanvps.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Speed and traffic of the VPN: the byte counters of the core's own inbounds (socks + http,
 * everything that goes through the VPN in both modes), read once a second from Xray's
 * metrics page while Главная is on screen. Down = what the core hands to the programs.
 */
class TrafficMonitor(private val scope: CoroutineScope) {
    data class Sample(val id: Int, val down: Double, val up: Double)

    /** Bytes per second now. */
    var down by mutableStateOf(0.0); private set
    var up by mutableStateOf(0.0); private set
    /** The last [WINDOW] seconds, for the faint curve behind the status. */
    var history by mutableStateOf<List<Sample>>(emptyList()); private set

    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
    private var job: Job? = null
    private var port = 0
    private var last: Triple<Long, Long, Long>? = null
    private var tick = 0

    /** Reads the core on [metricsPort]; another port (a new connection) starts the chart over. */
    fun start(metricsPort: Int) {
        if (metricsPort <= 0) return
        if (metricsPort != port) reset()
        port = metricsPort
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                withContext(Dispatchers.IO) { counters(port) }?.let(::sample)
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        last = null
        down = 0.0
        up = 0.0
    }

    private fun reset() {
        last = null
        history = emptyList()
        down = 0.0
        up = 0.0
    }

    private fun sample(now: Pair<Long, Long>) {
        val at = System.currentTimeMillis()
        val prev = last
        last = Triple(now.first, now.second, at)
        // First look, or the core started again (counters back at zero).
        if (prev == null || now.first < prev.first || now.second < prev.second) return
        val seconds = ((at - prev.third) / 1000.0).coerceAtLeast(0.2)
        down = (now.first - prev.first) / seconds
        up = (now.second - prev.second) / seconds
        tick += 1
        history = (history + Sample(tick, down, up)).takeLast(WINDOW)
    }

    /** Total downlink and uplink of our inbounds, or null while the core doesn't answer. */
    private fun counters(port: Int): Pair<Long, Long>? = runCatching {
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/debug/vars").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val inbound = JSONObject(r.body.string()).optJSONObject("stats")?.optJSONObject("inbound") ?: return null
            var d = 0L
            var u = 0L
            for (tag in inbound.keys()) {
                val o = inbound.optJSONObject(tag) ?: continue
                d += o.optLong("downlink")
                u += o.optLong("uplink")
            }
            d to u
        }
    }.getOrNull()

    companion object {
        const val WINDOW = 60
    }
}

/**
 * Проверить соединение (as on iPhone): a request through the running core itself, so it
 * shows whether the VPN carries traffic right now, how long a fresh HTTPS request takes and
 * which address the sites see.
 */
object ConnectionCheck {
    /** The same page a ping loads. */
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class Ok(val ms: Long, val ip: String?, val country: String?) : Status
        data class Failed(val why: String) : Status
    }

    /** [proxyPort]: the core's local HTTP inbound. */
    suspend fun run(proxyPort: Int): Status = withContext(Dispatchers.IO) {
        // A new client each time: a new connection, as a ping measures it.
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
            .build()
        val exit = async { exitAddress(client) }
        val start = System.nanoTime()
        val why = try {
            client.newCall(Request.Builder().url(PROBE_URL).head().header("User-Agent", Config.TITAN_UA).build()).execute().close()
            val ms = (System.nanoTime() - start) / 1_000_000
            val e = exit.await()
            return@withContext Status.Ok(ms, e?.first, e?.second)
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        // Google didn't answer but Cloudflare did: the VPN carries traffic (the panel's
        // routing may send Google another way), so it isn't "no answer".
        val e = exit.await()
        if (e?.first != null && e.third != null) Status.Ok(e.third!!, e.first, e.second) else Status.Failed(why)
    }

    /** The exit's address and country from Cloudflare's trace (ip= and loc=), and how long it took. */
    private fun exitAddress(client: OkHttpClient): Triple<String?, String?, Long?>? = runCatching {
        val start = System.nanoTime()
        client.newCall(Request.Builder().url("https://1.1.1.1/cdn-cgi/trace").header("User-Agent", Config.TITAN_UA).build()).execute().use { r ->
            val ms = (System.nanoTime() - start) / 1_000_000
            val fields = r.body.string().lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            Triple(fields["ip"], fields["loc"], ms)
        }
    }.getOrNull()
}
