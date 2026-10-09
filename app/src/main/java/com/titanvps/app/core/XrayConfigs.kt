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
    const val LOCAL_PROXY_TAG = "app-in"
    private const val SNIFFING_KEY = "_titanSniffing"
    const val ACCESS_LOG = "xray-access.log"
    const val ERROR_LOG = "xray-error.log"
    private const val PROXY_TAG = "proxy"
    private const val BLOCK_TAG = "titan-block"
    private val YOUTUBE_DOMAINS = listOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com",
        "youtube.googleapis.com", "ggpht.com", "youtube-nocookie.com",
    )
    private val UPDATE_DOMAINS = listOf("github.com", "githubusercontent.com", "githubassets.com")
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

    /**
     * Experimental TCP Fast Open: hosts whose name (or Host Mapper "titan" field) says "tfo"
     * dial with TFO, so it can be tried on one host without touching the others.
     */
    private fun applyTfo(name: String, proxy: JSONObject) {
        if ("tfo" !in name.lowercase() && "tfo" !in proxy.optString("titan").lowercase()) return
        val stream = proxy.optJSONObject("streamSettings") ?: JSONObject().also { proxy.put("streamSettings", it) }
        val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
        sockopt.put("tcpFastOpen", true)
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
        // Keep the panel's sniffing settings for our TUN inbound (routeOnly etc.).
        (0 until (inbounds?.length() ?: 0)).firstNotNullOfOrNull { inbounds!!.optJSONObject(it)?.optJSONObject("sniffing") }
            ?.let { cfg.put(SNIFFING_KEY, it) }
        cfg.remove("inbounds")
        retargetInboundRules(cfg, oldTags)
        applyTfo(name, proxy)
        return Server(id = "json-$index-${name.hashCode()}", name = name, xrayJson = cfg.toString(), proxyTag = proxyTag)
    }

    /**
     * Outbounds worth pinging for a server. Configs that route the catch-all rule to a
     * balancer (e.g. BAL-LTE over bal, bal-2, …) are measured on its members — the
     * balancer picks a live one, so one dead member mustn't show the server as down.
     */
    fun pingTags(serverJson: String, proxyTag: String, max: Int = 100): List<String> {
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

    /**
     * Identity of an outbound regardless of its tag, so the same node that appears in
     * several configs (a country and АВТО) is pinged once.
     */
    fun endpointKey(serverJson: String, tag: String): String? {
        val outbounds = runCatching { JSONObject(serverJson).getJSONArray("outbounds") }.getOrNull() ?: return null
        val byTag = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }.associateBy { it.optString("tag") }
        val ob = byTag[tag] ?: return null
        val copy = JSONObject(ob.toString()).apply { remove("tag") }
        // Include what it dials through (dialerProxy), otherwise two chains could collide.
        val via = ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")
            ?.takeIf { it.isNotEmpty() }?.let { byTag[it] }?.let { JSONObject(it.toString()).apply { remove("tag") } }
        return copy.toString() + (via?.toString() ?: "")
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
    fun buildRunConfig(
        serverJson: String,
        tunFd: Int,
        assetDir: String,
        mtu: Int,
        logDir: String? = null,
        proxyTag: String? = null,
        localProxy: com.titanvps.app.vpn.LocalProxy? = null,
    ): String {
        val cfg = JSONObject(serverJson)

        // Access log shows every connection and where routing sent it (direct/proxy/block);
        // error log shows why something failed. Both are read by the diagnostics screen.
        if (logDir != null) {
            cfg.put("log", JSONObject()
                .put("loglevel", "warning")
                .put("access", "$logDir/$ACCESS_LOG")
                .put("error", "$logDir/$ERROR_LOG"))
        }

        // The panel's sniffing if it had one. Default routeOnly=false: the destination
        // becomes the sniffed domain, so "direct" re-resolves it and falls back to IPv4
        // instead of dialing an unreachable IPv6 address (ERR_CONNECTION_CLOSED).
        val sniffing = (cfg.remove(SNIFFING_KEY) as? JSONObject)?.put("enabled", true)
            ?: JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                .put("routeOnly", false)

        cfg.put("env", (cfg.optJSONObject("env") ?: JSONObject())
            .put("xray.tun.fd", tunFd.toString())
            .put("xray.location.asset", assetDir))

        val inbounds = JSONArray().put(
            JSONObject()
                .put("tag", TUN_TAG)
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "titan0").put("mtu", mtu))
                .put("sniffing", sniffing)
        )
        localProxy?.let { lp ->
            inbounds.put(
                JSONObject()
                    .put("tag", LOCAL_PROXY_TAG)
                    .put("protocol", "http")
                    .put("listen", "127.0.0.1")
                    .put("port", lp.port)
                    .put("settings", JSONObject().put("accounts", JSONArray().put(
                        JSONObject().put("user", lp.user).put("pass", lp.password)
                    )))
            )
        }
        cfg.put("inbounds", inbounds)

        if (!cfg.has("dns")) {
            cfg.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
        }
        // IPv4 answers only (like Happ by default): apps never try IPv6, which breaks
        // "direct" routes on networks without IPv6.
        cfg.getJSONObject("dns").put("queryStrategy", "UseIPv4")

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
        // YouTube over QUIC (UDP 443) breaks playback through proxies ("Ошибка
        // воспроизведения"); blocking it makes the app fall back to TCP, like Happ does.
        val blockTag = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .firstOrNull { it.optString("protocol") == "blackhole" }?.optString("tag")
            ?: BLOCK_TAG.also { outbounds.put(JSONObject().put("tag", it).put("protocol", "blackhole")) }
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("network", "udp")
                .put("port", "443")
                .put("domain", JSONArray(YOUTUBE_DOMAINS.map { "domain:$it" }))
                .put("outboundTag", blockTag)
        )
        // App updates come from GitHub and must always go through the server, whatever
        // the panel's rules say (GitHub is slow or blocked on many Russian networks).
        if (proxyTag != null) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("domain", JSONArray(UPDATE_DOMAINS.map { "domain:$it" }))
                    .put("outboundTag", proxyTag)
            )
        }
        for (i in 0 until oldRules.length()) rules.put(oldRules.get(i))
        routing.put("rules", rules)

        return cfg.toString()
    }
}
