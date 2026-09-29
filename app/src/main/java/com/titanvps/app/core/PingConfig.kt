package com.titanvps.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One Xray config for pinging many servers at once: a local SOCKS inbound per server,
 * each routed to that server's proxy outbound (plus its dialerProxy chain).
 */
object PingConfig {

    data class Item(val xrayJson: String, val proxyTag: String)

    fun build(items: List<Item>, ports: List<Int>): String {
        require(items.size == ports.size)
        val inbounds = JSONArray()
        val outbounds = JSONArray().put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val rules = JSONArray()

        items.forEachIndexed { i, item ->
            val src = JSONObject(item.xrayJson).optJSONArray("outbounds") ?: JSONArray()
            val byTag = (0 until src.length()).map { src.getJSONObject(it) }
                .filter { it.optString("tag").isNotEmpty() }
                .associateBy { it.getString("tag") }
            val proxy = byTag[item.proxyTag] ?: return@forEachIndexed

            // Copy the proxy outbound and whatever it dials through, under unique tags.
            val renamed = linkedMapOf<String, String>()
            fun add(tag: String) {
                if (tag in renamed) return
                val ob = byTag[tag] ?: return
                renamed[tag] = "p$i-$tag"
                ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy")
                    ?.takeIf { it.isNotEmpty() }?.let(::add)
            }
            add(proxy.getString("tag"))
            for ((orig, tag) in renamed) {
                val ob = JSONObject(byTag.getValue(orig).toString()).put("tag", tag)
                ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.let { so ->
                    so.optString("dialerProxy").takeIf { it.isNotEmpty() }?.let { dp ->
                        renamed[dp]?.let { so.put("dialerProxy", it) } ?: so.remove("dialerProxy")
                    }
                }
                outbounds.put(ob)
            }

            inbounds.put(
                JSONObject()
                    .put("tag", "in$i")
                    .put("protocol", "socks")
                    .put("listen", "127.0.0.1")
                    .put("port", ports[i])
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
            )
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("in$i"))
                    .put("outboundTag", renamed.getValue(proxy.getString("tag")))
            )
        }

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .toString()
    }
}
