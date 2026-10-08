package com.titanvps.app.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * NaiveProxy and Mieru servers. Xray has neither, so for such a server we run the
 * protocol's own client (bundled as libnaive.so / libmieru.so) as a local SOCKS proxy and
 * point the server's proxy outbound at it; routing, bypasses and ping stay Xray's.
 *
 * In the panel these are ordinary hosts (on placeholder inbounds) whose name contains
 * "Naive" or "Mieru"; their outbound carries the server's address, port and the user's
 * secret (VLESS id or Shadowsocks password). Login and password on the node are derived
 * from that secret the same way as server/titan-node-sync.py does.
 */
object Plugins {
    enum class Kind(val lib: String) { NAIVE("libnaive.so"), MIERU("libmieru.so") }

    data class Endpoint(val kind: Kind, val host: String, val port: Int, val secret: String) {
        /** URL-safe credentials, same derivation as the node sync. */
        val user: String get() = derived().substring(0, 16)
        val password: String get() = derived().substring(16, 48)
        private fun derived(): String = MessageDigest.getInstance("SHA-256")
            .digest("titan:$secret".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            "naive" in n -> Kind.NAIVE
            "mieru" in n -> Kind.MIERU
            else -> null
        }
    }

    /** The plugin endpoint of a server, or null for a regular Xray server. */
    fun endpoint(name: String, xrayJson: String, proxyTag: String): Endpoint? {
        val kind = kindOf(name) ?: return null
        val outbound = outbounds(xrayJson)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("tag") == proxyTag }
        } ?: return null
        val settings = outbound.optJSONObject("settings") ?: return null
        settings.optJSONArray("servers")?.optJSONObject(0)?.let { s ->
            val secret = s.optString("password")
            if (s.optString("address").isNotEmpty() && secret.isNotEmpty()) {
                return Endpoint(kind, s.optString("address"), s.optInt("port"), secret)
            }
        }
        settings.optJSONArray("vnext")?.optJSONObject(0)?.let { v ->
            val secret = v.optJSONArray("users")?.optJSONObject(0)?.optString("id").orEmpty().lowercase()
            if (v.optString("address").isNotEmpty() && secret.isNotEmpty()) {
                return Endpoint(kind, v.optString("address"), v.optInt("port"), secret)
            }
        }
        return null
    }

    /** The same config with its proxy outbound sent to our local SOCKS on [port]. */
    fun withLocalSocks(xrayJson: String, proxyTag: String, port: Int): String {
        val cfg = JSONObject(xrayJson)
        val arr = cfg.optJSONArray("outbounds") ?: return xrayJson
        for (i in 0 until arr.length()) {
            val ob = arr.optJSONObject(i) ?: continue
            if (ob.optString("tag") != proxyTag) continue
            arr.put(
                i,
                JSONObject()
                    .put("tag", proxyTag)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", port)))),
            )
        }
        return cfg.toString()
    }

    private fun outbounds(json: String): JSONArray? = runCatching { JSONObject(json).optJSONArray("outbounds") }.getOrNull()
}

/**
 * One running plugin client (naive / mieru) listening on a local SOCKS port. Our app is
 * excluded from the VPN, so the client's own connections go straight to the server.
 */
class PluginProcess(private val context: Context, private val endpoint: Plugins.Endpoint) : Closeable {
    private var process: Process? = null
    val port: Int = java.net.ServerSocket(0).use { it.localPort }

    /** Starts the client and waits until its SOCKS port answers. */
    fun start() {
        val exe = File(context.applicationInfo.nativeLibraryDir, endpoint.kind.lib)
        note("${endpoint.kind}: start ${endpoint.host}:${endpoint.port} → 127.0.0.1:$port, exe=${exe.exists()} ${exe.canExecute()}")
        if (!exe.exists()) error("Этот сервер пока не поддерживается на вашем устройстве")
        val dir = File(context.filesDir, "plugins").apply { mkdirs() }
        val log = File(context.cacheDir, "${endpoint.kind.name.lowercase()}.log")
        val pb = when (endpoint.kind) {
            Plugins.Kind.NAIVE -> ProcessBuilder(
                exe.absolutePath,
                "--log",
                "--listen=socks://127.0.0.1:$port",
                "--proxy=https://${endpoint.user}:${endpoint.password}@${endpoint.host}:${endpoint.port}",
            )
            Plugins.Kind.MIERU -> {
                val config = File(dir, "mieru-$port.json")
                config.writeText(mieruConfig())
                ProcessBuilder(exe.absolutePath, "run").apply {
                    environment()["MIERU_CONFIG_JSON_FILE"] = config.absolutePath
                    environment()["HOME"] = dir.absolutePath
                }
            }
        }
        process = pb.directory(dir).redirectErrorStream(true).redirectOutput(log).start()
        // Ready when the local SOCKS port accepts connections (up to ~6 s).
        repeat(60) {
            if (process?.isAlive != true) {
                val tail = runCatching { log.readLines().takeLast(5).joinToString(" | ") }.getOrDefault("")
                Log.w("Titan", "${endpoint.kind} exited: $tail")
                note("${endpoint.kind}: exited ${runCatching { process?.exitValue() }.getOrNull()}: $tail")
                error("Не удалось подключиться к серверу")
            }
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) {
                note("${endpoint.kind}: ready on $port")
                return
            }
            Thread.sleep(100)
        }
        note("${endpoint.kind}: no SOCKS on $port after 6 s")
        close()
        error("Не удалось подключиться к серверу")
    }

    /** One line in plugins.log (shown in Диагностика) — no secrets. */
    private fun note(line: String) = runCatching {
        val f = File(context.cacheDir, PLUGINS_LOG)
        if (f.length() > 64 * 1024) f.writeText("")
        f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line\n")
    }

    companion object {
        const val PLUGINS_LOG = "plugins.log"
    }

    /** Mieru wants an IP: Go's resolver has no system DNS on Android, so we resolve here. */
    private fun mieruConfig(): String {
        val ip = runCatching { InetAddress.getByName(endpoint.host).hostAddress }.getOrNull()
        val server = JSONObject()
            .put("portBindings", JSONArray().put(JSONObject().put("port", endpoint.port).put("protocol", "TCP")))
        if (ip != null) server.put("ipAddress", ip) else server.put("domainName", endpoint.host)
        return JSONObject()
            .put(
                "profiles", JSONArray().put(
                    JSONObject()
                        .put("profileName", "titan")
                        .put("user", JSONObject().put("name", endpoint.user).put("password", endpoint.password))
                        .put("servers", JSONArray().put(server))
                        .put("mtu", 1400),
                ),
            )
            .put("activeProfile", "titan")
            .put("socks5Port", port)
            .put("loggingLevel", "WARN")
            .toString()
    }

    override fun close() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
    }
}
