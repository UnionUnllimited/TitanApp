package com.titanvps.app.core

import com.titanvps.app.data.Server
import org.json.JSONObject

/**
 * Automatic way around a server whose IP is blocked on the user's network: its connection
 * is carried by another of the user's servers that is reachable (Xray dialerProxy), so the
 * exit stays the blocked server. Picked by ping: a server that times out directly but
 * answers through the fastest reachable one is used that way.
 */
object Relay {
    const val TAG = "titan-relay"

    /** Id under which a server is measured through a relay. */
    const val PROBE_PREFIX = "relay:"

    /** The one plain Xray outbound of a server (no balancer, no chain, no plugin), or null. */
    private fun plainOutbound(server: Server): JSONObject? {
        if (Plugins.endpoint(server.name, server.xrayJson, server.proxyTag) != null) return null
        if (XrayConfigs.pingTags(server.xrayJson, server.proxyTag) != listOf(server.proxyTag)) return null
        val arr = runCatching { JSONObject(server.xrayJson).optJSONArray("outbounds") }.getOrNull() ?: return null
        val ob = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("tag") == server.proxyTag }
            ?: return null
        if (ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy").orEmpty().isNotEmpty()) return null
        address(ob) ?: return null
        return ob
    }

    private fun address(ob: JSONObject): String? {
        val s = ob.optJSONObject("settings") ?: return null
        return (s.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")
            ?: s.optJSONArray("servers")?.optJSONObject(0)?.optString("address")
            ?: s.optString("address")).takeIf { !it.isNullOrEmpty() }
    }

    fun canChain(server: Server): Boolean = plainOutbound(server) != null

    /** The fastest server that answered directly and can carry [target] (another address). */
    fun pick(servers: List<Server>, pings: Map<String, Long>, target: Server): Server? {
        val targetHost = plainOutbound(target)?.let(::address) ?: return null
        return servers
            .filter { it.id != target.id && (pings[it.id] ?: -1) >= 0 }
            .filter { s -> plainOutbound(s)?.let(::address)?.let { it != targetHost } == true }
            .minByOrNull { pings.getValue(it.id) }
    }

    /** [json] (the server's config) with its proxy outbound dialing through [relay]. */
    fun chain(json: String, proxyTag: String, relay: Server): String {
        val via = plainOutbound(relay) ?: return json
        val cfg = JSONObject(json)
        val arr = cfg.optJSONArray("outbounds") ?: return json
        val proxy = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("tag") == proxyTag }
            ?: return json
        val stream = proxy.optJSONObject("streamSettings") ?: JSONObject().also { proxy.put("streamSettings", it) }
        val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
        sockopt.put("dialerProxy", TAG)
        arr.put(JSONObject(via.toString()).put("tag", TAG))
        return cfg.toString()
    }
}
