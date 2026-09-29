package com.titanvps.app.core

import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin wrapper over libXray's single `Invoke` entrypoint (API v3).
 * See https://github.com/XTLS/libXray#api
 *
 * Note: libXray allows only one managed Xray instance per process, and ping
 * is rejected while the core is running.
 */
object XrayCore {

    private const val PING_URL = "http://www.gstatic.com/generate_204"

    class XrayException(message: String) : Exception(message)

    private fun invoke(method: String, payload: JSONObject = JSONObject()): Any? {
        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", method)
            .put("payload", payload)
        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success")) {
            throw XrayException(response.optString("error").ifBlank { "$method failed" })
        }
        return response.opt("data")
    }

    fun version(): String =
        (invoke("xrayVersion") as? JSONObject)?.optString("version").orEmpty()

    /** Share links / base64 subscription → list of outbound JSON objects. */
    fun convertShareLinks(text: String): List<JSONObject> {
        val data = invoke("convertShareLinksToXrayJson", JSONObject().put("text", text)) as JSONObject
        val arr = data.optJSONArray("outbounds") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Volatile private var pingDnsReady = false

    /**
     * Android has no resolv.conf, so Go's resolver falls back to a loopback DNS that
     * doesn't exist. Must be set before pinging, like before runXray.
     */
    @Synchronized
    fun ensurePingDns() {
        if (pingDnsReady) return
        LibXray.setDNS(object : DialerController {
            // The app is excluded from the VPN, sockets need no protect() here.
            override fun protectFd(fd: Long): Boolean = true
        }, "1.1.1.1:53")
        pingDnsReady = true
    }

    /** Delay in ms per item (-1 on failure) and the per-item error text. */
    fun ping(items: List<Pair<String, String>>, timeoutSec: Int = 5): List<Pair<Long, String?>> {
        val configs = JSONArray()
        items.forEach { (json, tag) -> configs.put(JSONObject().put("xrayJson", json).put("outboundTag", tag)) }
        val data = invoke(
            "pingBatch",
            JSONObject()
                .put("configs", configs)
                .put("timeout", timeoutSec)
                // Plain HTTP: no extra TLS handshake inside the tunnel, so the number
                // reflects the server's latency (same approach as Happ / v2rayNG).
                .put("url", PING_URL),
        ) as JSONObject
        val results = data.optJSONArray("results") ?: JSONArray()
        return (0 until items.size).map { i ->
            val r = results.optJSONObject(i)
            if (r != null && r.optBoolean("success")) r.optLong("delay") to null
            else -1L to (r?.optString("error")?.takeIf { it.isNotBlank() } ?: "нет ответа")
        }
    }

    fun start(configJson: String, protect: (Int) -> Boolean) {
        val controller = object : DialerController {
            override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
        }
        // Go resolver must use a protected socket, not the VPN's loopback DNS.
        LibXray.setDNS(controller, "1.1.1.1:53")
        LibXray.registerDialerController(controller)
        invoke("runXray", JSONObject().put("xrayJson", configJson))
    }

    fun stop() {
        runCatching { invoke("stopXray") }
        runCatching { LibXray.resetDNS() }
    }

    fun isRunning(): Boolean =
        runCatching { (invoke("getXrayState") as? JSONObject)?.optBoolean("running") == true }.getOrDefault(false)
}
