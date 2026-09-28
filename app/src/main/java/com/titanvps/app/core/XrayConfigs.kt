package com.titanvps.app.core

import com.titanvps.app.data.Server
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns subscription content into [Server]s and builds the final config that Xray runs.
 *
 * Preferred: Remnawave returns full Xray JSON configs (array), so routing, DNS etc. are
 * fully controlled from the panel. Fallback: share links (vless://…) wrapped into a
 * minimal default config.
 */
object XrayConfigs {

    const val TUN_TAG = "tun-in"
    const val DNS_OUT_TAG = "dns-out"
    private const val PROXY_TAG = "proxy"
    private val SERVICE_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    /** Returns true if the body looks like Xray JSON (object or array of configs). */
    fun isXrayJson(body: String): Boolean {
        val t = body.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    /** Parses one Xray config or an array of them (Remnawave "Xray JSON" response). */
    fun serversFromXrayJson(body: String): List<Server> {
        val t = body.trim()
        val configs = if (t.startsWith("[")) {
            val arr = JSONArray(t)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } else {
            listOf(JSONObject(t))
        }
        return configs.mapIndexedNotNull { i, cfg -> serverFromConfig(cfg, i) }
    }

    /** Wraps outbounds produced by libXray's `convertShareLinksToXrayJson`. */
    fun serversFromOutbounds(outbounds: List<JSONObject>): List<Server> =
        outbounds.mapIndexed { i, ob ->
            val name = ob.optString("tag").ifBlank { "Сервер ${i + 1}" }
            val proxy = JSONObject(ob.toString()).put("tag", PROXY_TAG)
            val cfg = defaultConfig(proxy)
            Server(id = "link-$i-${name.hashCode()}", name = name, xrayJson = cfg.toString(), proxyTag = PROXY_TAG)
        }

    private fun serverFromConfig(src: JSONObject, index: Int): Server? {
        val cfg = JSONObject(src.toString())
        val outbounds = cfg.optJSONArray("outbounds") ?: return null
        val proxyTag = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .firstOrNull { it.optString("protocol") !in SERVICE_PROTOCOLS }
            ?.optString("tag")?.takeIf { it.isNotEmpty() }
            ?: return null
        val name = cfg.optString("remarks").ifBlank { "Сервер ${index + 1}" }
        cfg.remove("remarks")
        cfg.remove("inbounds")
        return Server(id = "json-$index-${name.hashCode()}", name = name, xrayJson = cfg.toString(), proxyTag = proxyTag)
    }

    private fun defaultConfig(proxy: JSONObject): JSONObject = JSONObject()
        .put("log", JSONObject().put("loglevel", "warning"))
        .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
        .put(
            "outbounds", JSONArray()
                .put(proxy)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        )
        .put(
            "routing", JSONObject()
                .put("domainStrategy", "IPIfNonMatch")
                .put(
                    "rules", JSONArray().put(
                        JSONObject()
                            .put("type", "field")
                            .put("ip", JSONArray().put("geoip:private"))
                            .put("outboundTag", "direct")
                    )
                )
        )

    /**
     * Final config: our TUN inbound (fd from VpnService), DNS hijack, asset dir.
     * Everything else (outbounds, routing, dns) comes from the server.
     */
    fun buildRunConfig(serverJson: String, tunFd: Int, assetDir: String, mtu: Int): String {
        val cfg = JSONObject(serverJson)

        cfg.put("env", (cfg.optJSONObject("env") ?: JSONObject())
            .put("xray.tun.fd", tunFd.toString())
            .put("xray.location.asset", assetDir))

        cfg.put("inbounds", JSONArray().put(
            JSONObject()
                .put("tag", TUN_TAG)
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "titan0").put("mtu", mtu))
                .put("sniffing", JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    .put("routeOnly", true))
        ))

        if (!cfg.has("dns")) {
            cfg.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
        }

        val outbounds = cfg.getJSONArray("outbounds")
        val hasDnsOut = (0 until outbounds.length()).any { outbounds.getJSONObject(it).optString("tag") == DNS_OUT_TAG }
        if (!hasDnsOut) outbounds.put(JSONObject().put("tag", DNS_OUT_TAG).put("protocol", "dns"))

        // All DNS queries from the device go to Xray's DNS module first.
        val routing = cfg.optJSONObject("routing") ?: JSONObject().also { cfg.put("routing", it) }
        val oldRules = routing.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray().put(
            JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray().put(TUN_TAG))
                .put("port", "53")
                .put("outboundTag", DNS_OUT_TAG)
        )
        for (i in 0 until oldRules.length()) rules.put(oldRules.get(i))
        routing.put("rules", rules)

        return cfg.toString()
    }
}
