package com.titanvps.desktop

import org.json.JSONArray
import org.json.JSONObject

/** Xray JSON from the subscription → servers, and the config xray.exe runs. */
object XrayConfigs {

    private const val PROXY_TAG = "proxy"
    private const val SNIFFING_KEY = "_titanSniffing"
    private const val INBOUND_KEY = "_titanInboundTags"
    const val SOCKS_TAG = "socks-in"
    const val HTTP_TAG = "http-in"
    const val ACCESS_LOG = "xray-access.log"
    const val ERROR_LOG = "xray-error.log"
    private const val BLOCK_TAG = "titan-block"
    /** YouTube's QUIC is blocked (as on Android and iPhone): its playback falls back to TCP. */
    private val YOUTUBE_DOMAINS = listOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com",
        "youtube.googleapis.com", "ggpht.com", "youtube-nocookie.com",
    )
    private val SERVICE_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    fun clean(body: String): String = body.removePrefix("﻿").trim()

    fun serversFromXrayJson(body: String): List<Server> {
        val t = clean(body)
        if (!t.startsWith("[") && !t.startsWith("{")) throw SubscriptionException("Подписка пришла не в формате Xray JSON")
        val configs = if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        else listOf(JSONObject(t))
        var unlimited = false
        val servers = configs.mapIndexedNotNull { i, cfg ->
            serverFromConfig(cfg, i, mayBeUnlimited = !unlimited)?.also { if (it.id == ServerGroups.UNLIMITED_ID) unlimited = true }
        }
        return ServerGroups.placeUnlimited(servers)
    }

    /**
     * Xray 26.9 refuses configs with tlsSettings.allowInsecure (removed in favour of
     * certificate pinning). Dropping it keeps such a server starting; certificates are then
     * verified as usual.
     */
    private fun dropAllowInsecure(outbounds: JSONArray) {
        for (i in 0 until outbounds.length()) {
            outbounds.optJSONObject(i)?.optJSONObject("streamSettings")?.optJSONObject("tlsSettings")?.remove("allowInsecure")
        }
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

    /** [mayBeUnlimited]: no earlier row was taken as «Безлимитные · все серверы». */
    private fun serverFromConfig(src: JSONObject, index: Int, mayBeUnlimited: Boolean): Server? {
        val cfg = JSONObject(src.toString())
        val outbounds = cfg.optJSONArray("outbounds") ?: return null
        val proxy = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .firstOrNull { it.optString("protocol") !in SERVICE_PROTOCOLS } ?: return null
        if (proxy.optString("tag").isEmpty()) proxy.put("tag", PROXY_TAG)
        val name = cfg.optString("remarks").ifBlank { "Сервер ${index + 1}" }
        cfg.remove("remarks")
        // Our own local inbounds replace the panel's; remember its tags so routing rules
        // bound to them keep matching, and keep its sniffing settings.
        val inbounds = cfg.optJSONArray("inbounds")
        val tags = JSONArray()
        (0 until (inbounds?.length() ?: 0)).mapNotNull { inbounds!!.optJSONObject(it) }.forEach { ib ->
            ib.optString("tag").takeIf { it.isNotEmpty() }?.let { tags.put(it) }
            if (!cfg.has(SNIFFING_KEY)) ib.optJSONObject("sniffing")?.let { cfg.put(SNIFFING_KEY, it) }
        }
        cfg.put(INBOUND_KEY, tags)
        cfg.remove("inbounds")
        dropAllowInsecure(outbounds)
        applyTfo(name, proxy)
        val json = cfg.toString()
        val proxyTag = proxy.getString("tag")
        // «👇БЕЗЛИМИТНЫЕ👇» with a real config (the first such row; another stays a hidden header):
        // one name on every screen, one id wherever the panel puts it (as on Android and iPhone).
        if (mayBeUnlimited && ServerGroups.isUnlimitedRow(Server(id = "", name = name, xrayJson = json, proxyTag = proxyTag))) {
            return Server(id = ServerGroups.UNLIMITED_ID, name = ServerGroups.UNLIMITED_NAME, xrayJson = json, proxyTag = proxyTag)
        }
        return Server(id = "json-$index-${name.hashCode()}", name = name, xrayJson = json, proxyTag = proxyTag)
    }

    /**
     * Config for "system proxy" mode: local SOCKS + HTTP inbounds on [socksPort]/[httpPort];
     * outbounds, routing (Russian sites → direct etc.) and DNS come from the panel.
     * [sniffingEnable]: the subscription's `sniffing-enable`; false turns the inbounds' sniffing
     * off, null or true leave it as always (on). [accessLog]: every connection's line in
     * xray-access.log, only for Технический режим (as on iPhone: no list of visited sites on disk).
     */
    fun buildProxyConfig(
        serverJson: String, socksPort: Int, httpPort: Int, logDir: String, proxyTag: String? = null, metricsPort: Int = 0,
        sniffingEnable: Boolean? = null, accessLog: Boolean = false,
    ): String {
        val cfg = JSONObject(serverJson)
        val panelSniffing = cfg.remove(SNIFFING_KEY) as? JSONObject
        val sniffing = if (sniffingEnable == false) {
            // «sniffing-enable: 0»: the core sees only the IP a TUN app dialed, so the panel's
            // domain rules (and the YouTube QUIC block below) no longer match TUN traffic; its IP
            // rules still do. The system proxy's targets stay names (HTTP CONNECT), as do updates.
            JSONObject().put("enabled", false)
        } else {
            panelSniffing?.put("enabled", true)
                ?: JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    .put("routeOnly", false)
        }
        val oldTags = (cfg.remove(INBOUND_KEY) as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()

        cfg.put("log", JSONObject().put("loglevel", "warning")
            .put("access", if (accessLog) "$logDir/$ACCESS_LOG" else "none").put("error", "$logDir/$ERROR_LOG"))
        cfg.put("inbounds", JSONArray()
            .put(JSONObject().put("tag", SOCKS_TAG).put("protocol", "socks").put("listen", "127.0.0.1").put("port", socksPort)
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true)).put("sniffing", JSONObject(sniffing.toString())))
            .put(JSONObject().put("tag", HTTP_TAG).put("protocol", "http").put("listen", "127.0.0.1").put("port", httpPort)
                .put("sniffing", JSONObject(sniffing.toString()))))

        // Rules bound to the panel's inbound tags (or our Android "tun-in") → our two inbounds.
        val rules = cfg.optJSONObject("routing")?.optJSONArray("rules")
        for (i in 0 until (rules?.length() ?: 0)) {
            val rule = rules!!.optJSONObject(i) ?: continue
            val tags = rule.optJSONArray("inboundTag") ?: continue
            val list = (0 until tags.length()).map { tags.getString(it) }
            if (list.none { it in oldTags || it == "tun-in" }) continue
            rule.put("inboundTag", JSONArray((list.filterNot { it in oldTags || it == "tun-in" } + SOCKS_TAG + HTTP_TAG).distinct()))
        }
        val routing = cfg.optJSONObject("routing") ?: JSONObject().also { cfg.put("routing", it) }
        val old = routing.optJSONArray("rules") ?: JSONArray()
        val updated = JSONArray()
        // YouTube over QUIC (UDP 443) breaks playback through proxies («Ошибка воспроизведения»);
        // blocked, the browser falls back to TCP (as on Android and iPhone). UDP comes only from
        // the TUN (the system proxy carries none).
        val outbounds = cfg.optJSONArray("outbounds") ?: JSONArray().also { cfg.put("outbounds", it) }
        val blockTag = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("protocol") == "blackhole" && it.optString("tag").isNotEmpty() }?.optString("tag")
            ?: BLOCK_TAG.also { outbounds.put(JSONObject().put("tag", it).put("protocol", "blackhole")) }
        updated.put(
            JSONObject().put("type", "field").put("network", "udp").put("port", "443")
                .put("domain", JSONArray(YOUTUBE_DOMAINS.map { "domain:$it" }))
                .put("outboundTag", blockTag)
        )
        // App updates come from GitHub: always through the VPN, whatever the panel routes direct.
        if (proxyTag != null) {
            updated.put(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray().put("domain:github.com").put("domain:githubusercontent.com").put("domain:githubassets.com"))
                    .put("outboundTag", proxyTag)
            )
        }
        for (i in 0 until old.length()) updated.put(old.get(i))
        routing.put("rules", updated)
        if (!cfg.has("dns")) cfg.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
        cfg.getJSONObject("dns").put("queryStrategy", "UseIPv4")
        // Speed and traffic on Главная: byte counters of our inbounds, on a local metrics page.
        if (metricsPort > 0) {
            if (!cfg.has("stats")) cfg.put("stats", JSONObject())
            val policy = cfg.optJSONObject("policy") ?: JSONObject().also { cfg.put("policy", it) }
            val system = policy.optJSONObject("system") ?: JSONObject().also { policy.put("system", it) }
            system.put("statsInboundUplink", true).put("statsInboundDownlink", true)
            val metrics = cfg.optJSONObject("metrics") ?: JSONObject().put("tag", "titan-metrics").also { cfg.put("metrics", it) }
            metrics.put("listen", "127.0.0.1:$metricsPort")
        }
        return cfg.toString()
    }

    /** Outbounds worth pinging: balancer members if the catch-all rule goes to a balancer. */
    fun pingTags(serverJson: String, proxyTag: String, max: Int = 100): List<String> {
        val cfg = runCatching { JSONObject(serverJson) }.getOrNull() ?: return listOf(proxyTag)
        val routing = cfg.optJSONObject("routing") ?: return listOf(proxyTag)
        val rules = routing.optJSONArray("rules") ?: return listOf(proxyTag)
        val balancerTag = (0 until rules.length()).mapNotNull { rules.optJSONObject(it) }
            .lastOrNull { r ->
                r.optString("balancerTag").isNotEmpty() && !r.has("domain") && !r.has("ip") && !r.has("port") && !r.has("protocol")
            }?.optString("balancerTag") ?: return listOf(proxyTag)
        val balancers = routing.optJSONArray("balancers") ?: return listOf(proxyTag)
        val balancer = (0 until balancers.length()).mapNotNull { balancers.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == balancerTag } ?: return listOf(proxyTag)
        val selectors = balancer.optJSONArray("selector")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val outbounds = cfg.optJSONArray("outbounds") ?: return listOf(proxyTag)
        return (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .filter { it.optString("protocol") !in SERVICE_PROTOCOLS }
            .map { it.optString("tag") }
            .filter { tag -> tag.isNotEmpty() && selectors.any { tag.startsWith(it) } }
            .take(max).ifEmpty { listOf(proxyTag) }
    }

    /** Identity of an outbound regardless of its tag (same node in several configs → one probe). */
    fun endpointKey(serverJson: String, tag: String): String? {
        val outbounds = runCatching { JSONObject(serverJson).getJSONArray("outbounds") }.getOrNull() ?: return null
        val byTag = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }.associateBy { it.optString("tag") }
        val ob = byTag[tag] ?: return null
        val copy = JSONObject(ob.toString()).apply { remove("tag") }
        val via = ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")
            ?.takeIf { it.isNotEmpty() }?.let { byTag[it] }?.let { JSONObject(it.toString()).apply { remove("tag") } }
        return copy.toString() + (via?.toString() ?: "")
    }

    /**
     * The host a probe through [tag] dials first (lower case), for the ping's one probe at a
     * time per host ([PingRun.run]): the server of the last outbound of its dialerProxy chain
     * that has one (a chain's first hop; a fragment's freedom has none, the hop before it
     * dials), else its own. Null when there's none, or it's this PC (a protocol client's local
     * SOCKS: its own server is the host, see [entryHost]). As Android's PingConfig.dialHost.
     */
    fun dialHost(xrayJson: String, tag: String): String? {
        val src = runCatching { JSONObject(xrayJson).optJSONArray("outbounds") }.getOrNull() ?: return null
        val byTag = (0 until src.length()).mapNotNull { src.optJSONObject(it) }.associateBy { it.optString("tag") }
        var host: String? = null
        val seen = HashSet<String>()
        var next: String? = tag
        while (next != null && seen.add(next)) {
            val ob = byTag[next] ?: break
            val settings = ob.optJSONObject("settings")
            val server = settings?.optJSONArray("vnext")?.optJSONObject(0) ?: settings?.optJSONArray("servers")?.optJSONObject(0) ?: settings
            server?.optString("address")?.takeIf { it.isNotEmpty() }?.let { host = it.lowercase() }
            next = ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")?.takeIf { it.isNotEmpty() }
        }
        return host?.let(::remoteHost)
    }

    /**
     * The host a ping probe of [server] through [tag] dials first. [client]: the protocol
     * client (Naive, Mieru, sing-box) or Xray's own MASQUE its proxy outbound was replaced by
     * (Plugins.prepare): that client dials its real server, not the local SOCKS port the
     * probe's outbound points at, nor the dialerProxy chain the replaced outbound had.
     */
    fun entryHost(server: Server, tag: String, client: Plugins.Endpoint?): String? =
        if (client != null && tag == server.proxyTag) remoteHost(client.host.lowercase()) else dialHost(server.xrayJson, tag)

    /** [host] unless it is this PC (loopback): no rule for those. */
    private fun remoteHost(host: String): String? =
        host.takeUnless { it.isEmpty() || it == "localhost" || it == "::1" || it.startsWith("127.") }

    /** One config pinging many servers: a local SOCKS inbound per probe. */
    fun buildPingConfig(items: List<Pair<String, String>>, ports: List<Int>): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray().put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val rules = JSONArray()
        items.forEachIndexed { i, (json, proxyTag) ->
            val src = JSONObject(json).optJSONArray("outbounds") ?: JSONArray()
            val byTag = (0 until src.length()).map { src.getJSONObject(it) }
                .filter { it.optString("tag").isNotEmpty() }.associateBy { it.getString("tag") }
            if (proxyTag !in byTag) return@forEachIndexed
            val renamed = linkedMapOf<String, String>()
            fun add(tag: String) {
                if (tag in renamed) return
                val ob = byTag[tag] ?: return
                renamed[tag] = "p$i-$tag"
                ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")
                    ?.takeIf { it.isNotEmpty() }?.let(::add)
            }
            add(proxyTag)
            for ((orig, tag) in renamed) {
                val ob = JSONObject(byTag.getValue(orig).toString()).put("tag", tag)
                ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.let { so ->
                    so.optString("dialerProxy").takeIf { it.isNotEmpty() }?.let { dp ->
                        renamed[dp]?.let { so.put("dialerProxy", it) } ?: so.remove("dialerProxy")
                    }
                }
                outbounds.put(ob)
            }
            inbounds.put(JSONObject().put("tag", "in$i").put("protocol", "socks").put("listen", "127.0.0.1").put("port", ports[i])
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("in$i"))
                .put("outboundTag", renamed.getValue(proxyTag)))
        }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .toString()
    }
}
