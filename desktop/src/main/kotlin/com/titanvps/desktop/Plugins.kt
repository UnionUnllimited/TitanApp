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
 * NaiveProxy, Mieru, TUIC, AnyTLS and ShadowTLS servers, same as on Android: hosts named
 * "… Naive" / "… Mieru" / "… TUIC" / "… AnyTLS" / "… ShadowTLS" carry the node's address, port and the user's secret; we run the protocol's own
 * client (naive.exe / mieru.exe / sing-box.exe next to xray.exe) as a local SOCKS and point
 * Xray's proxy outbound at it. Credentials: sha256("titan:" + secret), as server/titan-node-sync.py.
 *
 * MASQUE ("… MASQUE" hosts) needs no separate client: Xray has it, so the proxy outbound
 * is rewritten into a masque one (HTTP/3; "TCP" or "H2" in the name: HTTP/2 over TLS).
 */
object Plugins {
    /** [exe]: the client we run as a local SOCKS; null when Xray speaks the protocol itself. */
    enum class Kind(val exe: String?) { NAIVE("naive.exe"), MIERU("mieru.exe"), TUIC("sing-box.exe"), ANYTLS("sing-box.exe"), SHADOWTLS("sing-box.exe"), MASQUE(null) }

    data class Endpoint(val kind: Kind, val host: String, val port: Int, val secret: String) {
        val user: String get() = derived().substring(0, 16)
        val password: String get() = derived().substring(16, 48)
        /** ShadowTLS: "server key:user key" of the Shadowsocks 2022 inside (as the node sync). */
        val ss2022Password: String get() = "${ss2022Key("titan-ss2022-server")}:${ss2022Key("titan-ss2022:$secret")}"
        /** TUIC needs a UUID too: the second half of the hash. */
        val tuicUuid: String get() = derived().substring(32, 64).let {
            "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}"
        }
        private fun derived(): String = MessageDigest.getInstance("SHA-256")
            .digest("titan:$secret".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun ss2022Key(text: String): String = java.util.Base64.getEncoder()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).copyOf(16))

    /** ShadowTLS handshakes with this real site (the node's "handshake" server). */
    const val SHADOWTLS_SNI = "www.amd.com"

    fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            "naive" in n -> Kind.NAIVE
            "mieru" in n -> Kind.MIERU
            "masque" in n -> Kind.MASQUE
            "tuic" in n -> Kind.TUIC
            "anytls" in n -> Kind.ANYTLS
            "shadowtls" in n -> Kind.SHADOWTLS
            else -> null
        }
    }

    fun endpoint(server: Server): Endpoint? {
        val kind = kindOf(server.name) ?: return null
        val arr = runCatching { JSONObject(server.xrayJson).optJSONArray("outbounds") }.getOrNull() ?: return null
        val ob = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("tag") == server.proxyTag }
            ?: return null
        val settings = ob.optJSONObject("settings") ?: return null
        settings.optJSONArray("servers")?.optJSONObject(0)?.let { s ->
            val secret = s.optString("password")
            if (s.optString("address").isNotEmpty() && secret.isNotEmpty()) return Endpoint(kind, s.optString("address"), s.optInt("port"), secret)
        }
        settings.optJSONArray("vnext")?.optJSONObject(0)?.let { v ->
            val secret = v.optJSONArray("users")?.optJSONObject(0)?.optString("id").orEmpty().lowercase()
            if (v.optString("address").isNotEmpty() && secret.isNotEmpty()) return Endpoint(kind, v.optString("address"), v.optInt("port"), secret)
        }
        return null
    }

    /** The server's config with its proxy outbound turned into Xray's own MASQUE client. */
    fun withMasque(xrayJson: String, proxyTag: String, endpoint: Endpoint, name: String): String {
        val n = name.lowercase()
        val http2 = "tcp" in n || "h2" in n
        val cfg = JSONObject(xrayJson)
        val arr = cfg.optJSONArray("outbounds") ?: return xrayJson
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("tag") != proxyTag) continue
            val tls = JSONObject().put("serverName", endpoint.host).put("alpn", JSONArray().put(if (http2) "h2" else "h3"))
            // Firefox: Russian DPI stalls the large Chrome ClientHello (seen with Samizdat).
            if (http2) tls.put("fingerprint", "firefox")
            arr.put(
                i,
                JSONObject().put("tag", proxyTag).put("protocol", "masque")
                    .put("settings", JSONObject().put("address", endpoint.host).put("port", endpoint.port))
                    .put(
                        "streamSettings",
                        JSONObject().put("network", "masque").put("security", "tls").put("tlsSettings", tls)
                            .put("masqueSettings", JSONObject().put("user", endpoint.user).put("pass", endpoint.password)),
                    ),
            )
        }
        return cfg.toString()
    }

    /**
     * The config to run for [server]: as is, MASQUE rewritten for Xray, or pointed at a
     * started [PluginProcess] (returned, to be closed by the caller).
     */
    fun prepare(server: Server): Pair<String, PluginProcess?> {
        val ep = endpoint(server) ?: return server.xrayJson to null
        if (ep.kind.exe == null) return withMasque(server.xrayJson, server.proxyTag, ep, server.name) to null
        val p = PluginProcess(ep)
        try {
            p.start()
        } catch (e: Exception) {
            p.close()
            throw e
        }
        return withLocalSocks(server.xrayJson, server.proxyTag, p.port) to p
    }

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
        val exe = File(AppPaths.coreDir, endpoint.kind.exe ?: error("${endpoint.kind} needs no client"))
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
            Plugins.Kind.TUIC, Plugins.Kind.ANYTLS, Plugins.Kind.SHADOWTLS -> {
                val config = File(dir, "${endpoint.kind.name.lowercase()}-$port.json").apply { writeText(singBoxConfig(endpoint.host)) }
                ProcessBuilder(exe.absolutePath, "run", "-c", config.absolutePath)
            }
            Plugins.Kind.MASQUE -> error("MASQUE needs no client")
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

    /**
     * sing-box as the client for TUIC / AnyTLS / ShadowTLS behind a local SOCKS (mixed) port.
     * [server] is what to dial (on Android: the resolved IP); TLS names stay the host's.
     */
    private fun singBoxConfig(server: String): String {
        val tls = { sni: String -> JSONObject().put("enabled", true).put("server_name", sni) }
        // Firefox: Russian DPI stalls the large Chrome ClientHello.
        val firefox = JSONObject().put("enabled", true).put("fingerprint", "firefox")
        val outbounds = JSONArray()
        when (endpoint.kind) {
            Plugins.Kind.TUIC -> outbounds.put(
                JSONObject().put("type", "tuic").put("tag", "proxy")
                    .put("server", server).put("server_port", endpoint.port)
                    .put("uuid", endpoint.tuicUuid).put("password", endpoint.password)
                    .put("congestion_control", "bbr").put("udp_relay_mode", "native")
                    .put("tls", tls(endpoint.host).put("alpn", JSONArray().put("h3"))),
            )
            Plugins.Kind.ANYTLS -> outbounds.put(
                JSONObject().put("type", "anytls").put("tag", "proxy")
                    .put("server", server).put("server_port", endpoint.port).put("password", endpoint.password)
                    .put("tls", tls(endpoint.host).put("utls", firefox)),
            )
            // ShadowTLS v3: a real TLS handshake with the cover site, then Shadowsocks 2022
            // whose per-user key tells the node who it is.
            Plugins.Kind.SHADOWTLS -> outbounds
                .put(
                    JSONObject().put("type", "shadowsocks").put("tag", "proxy")
                        .put("method", "2022-blake3-aes-128-gcm").put("password", endpoint.ss2022Password).put("detour", "shadowtls"),
                )
                .put(
                    JSONObject().put("type", "shadowtls").put("tag", "shadowtls")
                        .put("server", server).put("server_port", endpoint.port).put("version", 3).put("password", endpoint.password)
                        .put("tls", tls(Plugins.SHADOWTLS_SNI).put("utls", firefox)),
                )
            else -> error("${endpoint.kind} is not a sing-box client")
        }
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed").put("listen", "127.0.0.1").put("listen_port", port)))
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("final", "proxy"))
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
