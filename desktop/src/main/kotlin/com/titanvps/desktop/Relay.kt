package com.titanvps.desktop

import org.json.JSONObject

/**
 * Automatic way around a server whose IP is blocked on the user's network: its connection
 * is carried by another of the user's servers that is reachable (Xray dialerProxy), so the
 * exit stays the blocked server. Picked by ping: a server that times out directly but
 * answers through the fastest reachable one is used that way.
 */
object Relay {
    const val TAG = "titan-relay"

    /** The one plain outbound of a server (no balancer, no chain of its own), or null. */
    private fun plainOutbound(json: String, proxyTag: String): JSONObject? {
        if (XrayConfigs.pingTags(json, proxyTag) != listOf(proxyTag)) return null
        val arr = runCatching { JSONObject(json).optJSONArray("outbounds") }.getOrNull() ?: return null
        val ob = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("tag") == proxyTag }
            ?: return null
        if (ob.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.optString("dialerProxy").orEmpty().isNotEmpty()) return null
        val host = address(ob) ?: return null
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") return null // a local plugin client
        return ob
    }

    private fun address(ob: JSONObject): String? {
        val s = ob.optJSONObject("settings") ?: return null
        return (s.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")
            ?: s.optJSONArray("servers")?.optJSONObject(0)?.optString("address")
            ?: s.optString("address")).takeIf { !it.isNullOrEmpty() }
    }

    /**
     * Can [server] go through a relay: Xray's own client (VLESS, Trojan, Hysteria, MASQUE…).
     * Naive, Mieru and the sing-box ones dial by themselves and can't.
     */
    fun canChain(server: Server): Boolean {
        val kind = Plugins.endpoint(server)?.kind
        return (kind == null || kind == Plugins.Kind.MASQUE) && plainOutbound(server.xrayJson, server.proxyTag) != null
    }

    /** The fastest server that answered directly and can carry [target] (another address). */
    fun pick(servers: List<Server>, pings: Map<String, Long>, target: Server, exclude: Set<String> = emptySet()): Server? {
        val targetHost = plainOutbound(target.xrayJson, target.proxyTag)?.let(::address) ?: return null
        return servers
            .filter { it.id != target.id && it.id !in exclude && (pings[it.id] ?: -1) >= 0 }
            .filter { Plugins.endpoint(it) == null } // a plain Xray server: no client process to keep running
            .filter { s -> plainOutbound(s.xrayJson, s.proxyTag)?.let(::address)?.let { it != targetHost } == true }
            .minByOrNull { pings.getValue(it.id) }
    }

    /** [json] (the server's config, plugins already applied) with its proxy dialing through [relay]. */
    fun chain(json: String, proxyTag: String, relay: Server): String {
        val via = plainOutbound(relay.xrayJson, relay.proxyTag) ?: return json
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
