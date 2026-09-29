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
        val t = clean(body)
        return t.startsWith("{") || t.startsWith("[")
    }

    /** Strips a UTF-8 BOM and surrounding whitespace. */
    fun clean(body: String): String = body.removePrefix("\uFEFF").trim()

    /** Parses one Xray config or an array of them (Remnawave "Xray JSON" response). */
    fun serversFromXrayJson(body: String): List<Server> {
        val t = clean(body)
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
        val proxy = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .firstOrNull { it.optString("protocol") !in SERVICE_PROTOCOLS }
            ?: return null
        // Remnawave may omit the tag; ping and routing need one.
        if (proxy.optString("tag").isEmpty()) proxy.put("tag", PROXY_TAG)
        val proxyTag = proxy.getString("tag")
        val name = cfg.optString("remarks").ifBlank { "Сервер ${index + 1}" }
        cfg.remove("remarks")
        // Our TUN inbound replaces the config's inbounds; rules bound to those inbound
        // tags (e.g. "socks") must follow, or the panel's routing never matches.
        val inbounds = cfg.optJSONArray("inbounds")
        val oldTags = (0 until (inbounds?.length() ?: 0))
            .mapNotNull { inbounds!!.optJSONObject(it)?.optString("tag")?.takeIf { t -> t.isNotEmpty() } }
            .toSet()
        cfg.remove("inbounds")
        retargetInboundRules(cfg, oldTags)
        return Server(id = "json-$index-${name.hashCode()}", name = name, xrayJson = cfg.toString(), proxyTag = proxyTag)
    }

    /**
     * Outbounds worth pinging for a server. Configs that route the catch-all rule to a
     * balancer (e.g. BAL-LTE over bal, bal-2, …) are measured on its members — the
     * balancer picks a live one, so one dead member mustn't show the server as down.
     */
    fun pingTags(serverJson: String, proxyTag: String, max: Int = 4): List<String> {
        val cfg = runCatching { JSONObject(serverJson) }.getOrNull() ?: return listOf(proxyTag)
        val routing = cfg.optJSONObject("routing") ?: return listOf(proxyTag)
        val rules = routing.optJSONArray("rules") ?: return listOf(proxyTag)
        // The last rule that sends traffic to a balancer without domain/ip conditions.
        val balancerTag = (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }
            .lastOrNull { r ->
                r.optString("balancerTag").isNotEmpty() && !r.has("domain") && !r.has("ip") &&
                    !r.has("port") && !r.has("protocol")
            }?.optString("balancerTag") ?: return listOf(proxyTag)
        val balancers = routing.optJSONArray("balancers") ?: return listOf(proxyTag)
        val balancer = (0 until balancers.length()).mapNotNull { balancers.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == balancerTag } ?: return listOf(proxyTag)
        val selectors = balancer.optJSONArray("selector")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            .orEmpty()
        val outbounds = cfg.optJSONArray("outbounds") ?: return listOf(proxyTag)
        val members = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .filter { it.optString("protocol") !in SERVICE_PROTOCOLS }
            .map { it.optString("tag") }
            .filter { tag -> tag.isNotEmpty() && selectors.any { tag.startsWith(it) } }
        return members.take(max).ifEmpty { listOf(proxyTag) }
    }

    private fun retargetInboundRules(cfg: JSONObject, oldTags: Set<String>) {
        if (oldTags.isEmpty()) return
        val rules = cfg.optJSONObject("routing")?.optJSONArray("rules") ?: return
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val tags = rule.optJSONArray("inboundTag") ?: continue
            val list = (0 until tags.length()).map { tags.getString(it) }
            if (list.none { it in oldTags }) continue
            val mapped = (list.filterNot { it in oldTags } + TUN_TAG).distinct()
            rule.put("inboundTag", JSONArray(mapped))
        }
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
