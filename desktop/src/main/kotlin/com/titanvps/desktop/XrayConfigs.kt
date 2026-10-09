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
    private val SERVICE_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    fun clean(body: String): String = body.removePrefix("﻿").trim()

    fun serversFromXrayJson(body: String): List<Server> {
        val t = clean(body)
        if (!t.startsWith("[") && !t.startsWith("{")) throw SubscriptionException("Подписка пришла не в формате Xray JSON")
        val configs = if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        else listOf(JSONObject(t))
        return configs.mapIndexedNotNull { i, cfg -> serverFromConfig(cfg, i) }
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

    private fun serverFromConfig(src: JSONObject, index: Int): Server? {
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
        return Server(id = "json-$index-${name.hashCode()}", name = name, xrayJson = cfg.toString(), proxyTag = proxy.getString("tag"))
    }

    /**
     * Config for "system proxy" mode: local SOCKS + HTTP inbounds on [socksPort]/[httpPort];
     * outbounds, routing (Russian sites → direct etc.) and DNS come from the panel.
     */
    fun buildProxyConfig(serverJson: String, socksPort: Int, httpPort: Int, logDir: String, proxyTag: String? = null): String {
        val cfg = JSONObject(serverJson)
        val sniffing = (cfg.remove(SNIFFING_KEY) as? JSONObject)?.put("enabled", true)
            ?: JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                .put("routeOnly", false)
        val oldTags = (cfg.remove(INBOUND_KEY) as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()

        cfg.put("log", JSONObject().put("loglevel", "warning")
            .put("access", "$logDir/xray-access.log").put("error", "$logDir/xray-error.log"))
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
        // App updates come from GitHub: always through the VPN, whatever the panel routes direct.
        if (proxyTag != null) {
            val routing = cfg.optJSONObject("routing") ?: JSONObject().also { cfg.put("routing", it) }
            val old = routing.optJSONArray("rules") ?: JSONArray()
            val updated = JSONArray().put(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray().put("domain:github.com").put("domain:githubusercontent.com").put("domain:githubassets.com"))
                    .put("outboundTag", proxyTag)
            )
            for (i in 0 until old.length()) updated.put(old.get(i))
            routing.put("rules", updated)
        }
        if (!cfg.has("dns")) cfg.put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
        cfg.getJSONObject("dns").put("queryStrategy", "UseIPv4")
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
