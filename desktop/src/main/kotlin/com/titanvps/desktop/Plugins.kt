package com.titanvps.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * NaiveProxy, Mieru and Samizdat servers: hosts named "… Naive" / "… Mieru" / "… Samizdat"
 * carry the node's address, port and the user's secret; we run the protocol's own client
 * (naive.exe / mieru.exe / samizdat.exe next to xray.exe) as a local SOCKS and point Xray's
 * proxy outbound at it. Credentials: sha256("titan:" + secret), as server/titan-node-sync.py.
 *
 * Samizdat hosts sit on a VLESS + Reality placeholder inbound whose private key is the
 * Samizdat node's, so the subscription brings everything: Reality publicKey = the node's
 * key, serverName = the cover site; nothing about the node is built into the app.
 */
object Plugins {
    enum class Kind(val exe: String) { NAIVE("naive.exe"), MIERU("mieru.exe"), SAMIZDAT("samizdat.exe") }

    data class Endpoint(
        val kind: Kind,
        val host: String,
        val port: Int,
        val secret: String,
        /** Samizdat: server X25519 public key (hex) and cover site, from realitySettings. */
        val publicKey: String = "",
        val sni: String = "",
    ) {
        val user: String get() = derived().substring(0, 16)
        val password: String get() = derived().substring(16, 48)
        /** Samizdat short id (8 bytes as hex). */
        val shortId: String get() = derived().substring(48, 64)
        private fun derived(): String = MessageDigest.getInstance("SHA-256")
            .digest("titan:$secret".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            "naive" in n -> Kind.NAIVE
            "mieru" in n -> Kind.MIERU
            "samizdat" in n -> Kind.SAMIZDAT
            else -> null
        }
    }

    fun endpoint(server: Server): Endpoint? {
        val kind = kindOf(server.name) ?: return null
        val arr = runCatching { JSONObject(server.xrayJson).optJSONArray("outbounds") }.getOrNull() ?: return null
        val ob = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("tag") == server.proxyTag }
            ?: return null
        val settings = ob.optJSONObject("settings") ?: return null
        val reality = ob.optJSONObject("streamSettings")?.optJSONObject("realitySettings")
        val publicKey = reality?.optString("publicKey").orEmpty().let(::base64UrlToHex)
        val sni = reality?.optString("serverName").orEmpty()
        settings.optJSONArray("servers")?.optJSONObject(0)?.let { s ->
            val secret = s.optString("password")
            if (s.optString("address").isNotEmpty() && secret.isNotEmpty()) return Endpoint(kind, s.optString("address"), s.optInt("port"), secret)
        }
        settings.optJSONArray("vnext")?.optJSONObject(0)?.let { v ->
            val secret = v.optJSONArray("users")?.optJSONObject(0)?.optString("id").orEmpty().lowercase()
            if (v.optString("address").isNotEmpty() && secret.isNotEmpty()) {
                return Endpoint(kind, v.optString("address"), v.optInt("port"), secret, publicKey, sni)
            }
        }
        return null
    }

    /** Reality keys are base64url (no padding); samizdat-client takes hex. "" if not 32 bytes. */
    fun base64UrlToHex(key: String): String = runCatching {
        java.util.Base64.getUrlDecoder().decode(key.trim().trimEnd('='))
    }.getOrNull()?.takeIf { it.size == 32 }?.joinToString("") { "%02x".format(it) }.orEmpty()

    fun withLocalSocks(xrayJson: String, proxyTag: String, port: Int): String {
        val cfg = JSONObject(xrayJson)
        val arr = cfg.optJSONArray("outbounds") ?: return xrayJson
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("tag") != proxyTag) continue
            arr.put(
                i,
                JSONObject().put("tag", proxyTag).put("protocol", "socks")
                    .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", port)))),
            )
        }
        return cfg.toString()
    }
}

/** One running naive.exe / mieru.exe on a local SOCKS port. */
class PluginProcess(private val endpoint: Plugins.Endpoint) : Closeable {
    private var process: Process? = null
    val port: Int = freePorts(1).first()

    fun start() {
        val exe = File(AppPaths.coreDir, endpoint.kind.exe)
        if (!exe.exists()) throw IllegalStateException("Не найден ${exe.name}")
        val dir = File(AppPaths.dataDir, "plugins").apply { mkdirs() }
        val log = File(AppPaths.logDir, "${endpoint.kind.name.lowercase()}.log")
        val pb = when (endpoint.kind) {
            Plugins.Kind.NAIVE -> ProcessBuilder(
                exe.absolutePath, "--log", "--listen=socks://127.0.0.1:$port",
                "--proxy=https://${endpoint.user}:${endpoint.password}@${endpoint.host}:${endpoint.port}",
            )
            Plugins.Kind.MIERU -> {
                val config = File(dir, "mieru-$port.json").apply { writeText(mieruConfig()) }
                ProcessBuilder(exe.absolutePath, "run").apply {
                    environment()["MIERU_CONFIG_JSON_FILE"] = config.absolutePath
                }
            }
            Plugins.Kind.SAMIZDAT -> {
                if (endpoint.publicKey.isEmpty() || endpoint.sni.isEmpty()) throw IllegalStateException("Сервер пока не настроен")
                ProcessBuilder(
                    exe.absolutePath, "-listen", "127.0.0.1:$port",
                    "-server", "${endpoint.host}:${endpoint.port}", "-sni", endpoint.sni,
                    "-pubkey", endpoint.publicKey, "-sid", endpoint.shortId,
                )
            }
        }
        process = pb.directory(dir).redirectErrorStream(true).redirectOutput(log).start()
        repeat(60) {
            if (process?.isAlive != true) throw IllegalStateException("${endpoint.kind} не запустился")
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) return
            Thread.sleep(100)
        }
        close()
        throw IllegalStateException("${endpoint.kind} не отвечает")
    }

    private fun mieruConfig(): String {
        val ip = runCatching { InetAddress.getByName(endpoint.host).hostAddress }.getOrNull()
        val server = JSONObject().put("portBindings", JSONArray().put(JSONObject().put("port", endpoint.port).put("protocol", "TCP")))
        if (ip != null) server.put("ipAddress", ip) else server.put("domainName", endpoint.host)
        return JSONObject()
            .put("profiles", JSONArray().put(JSONObject()
                .put("profileName", "titan")
                .put("user", JSONObject().put("name", endpoint.user).put("password", endpoint.password))
                .put("servers", JSONArray().put(server))
                .put("mtu", 1400)))
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
