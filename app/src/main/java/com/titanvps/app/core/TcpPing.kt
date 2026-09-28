package com.titanvps.app.core

import com.titanvps.app.data.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP handshake latency to each server. Works with the VPN on or off: the app's own
 * sockets are excluded from the tunnel, so this measures the direct path.
 */
object TcpPing {

    /** Host and port of the server's proxy outbound, or null if unknown. */
    fun endpoint(server: Server): Pair<String, Int>? {
        val outbounds = runCatching { JSONObject(server.xrayJson).getJSONArray("outbounds") }.getOrNull() ?: return null
        val ob = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .firstOrNull { it.optString("tag") == server.proxyTag } ?: return null
        val s = ob.optJSONObject("settings") ?: return null
        val node = s.optJSONArray("vnext")?.optJSONObject(0)
            ?: s.optJSONArray("servers")?.optJSONObject(0)
            ?: s
        val host = node.optString("address").takeIf { it.isNotBlank() } ?: return null
        val port = node.optInt("port", 0).takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    /** Delay in ms, or -1 if unreachable. */
    fun ping(host: String, port: Int, timeoutMs: Int = 3000): Long = runCatching {
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - start) / 1_000_000
        }
    }.getOrDefault(-1L).coerceAtLeast(-1L)

    suspend fun pingAll(servers: List<Server>): Map<String, Long> = withContext(Dispatchers.IO) {
        coroutineScope {
            servers.map { s ->
                async { s.id to (endpoint(s)?.let { (h, p) -> ping(h, p).let { d -> if (d == 0L) 1L else d } } ?: -1L) }
            }.awaitAll().toMap()
        }
    }
}
