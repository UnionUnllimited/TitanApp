package com.titanvps.app.data

/** Splits locations into tabs. Bypass ("Обходы") servers are the only metered ones. */
object ServerGroups {

    enum class Group(val title: String) { SERVERS("Серверы"), BYPASS("Обходы") }

    /**
     * Not servers: section headers ("👇БЕЗЛИМИТНЫЕ👇", "ЛИМИТНЫЕ ОСТ: …") and info entries
     * the panel sends with a dummy address ("СЕРВЕР ДЛЯ", "ОБНОВЛЕНИЯ ПОДПИСКИ", …).
     */
    fun isHidden(server: Server): Boolean {
        val n = server.name.lowercase()
        return "лимитн" in n || isPlaceholder(server)
    }

    /** "Обходы" are exactly the servers with "обход" in the name (Обход 5, АВТО | Быстрые обходы). */
    fun isBypass(server: Server): Boolean = "обход" in server.name.lowercase()

    fun groupOf(server: Server, @Suppress("UNUSED_PARAMETER") all: List<Server> = emptyList()): Group =
        if (isBypass(server)) Group.BYPASS else Group.SERVERS

    /** Non-empty groups in display order; each keeps the subscription's order. */
    fun split(servers: List<Server>): List<Pair<Group, List<Server>>> {
        val (bypass, regular) = servers.partition(::isBypass)
        return listOf(Group.SERVERS to regular, Group.BYPASS to bypass).filter { it.second.isNotEmpty() }
    }

    /** The proxy outbound points nowhere (127.0.0.1, 0.0.0.0, port 0/1): an info entry. */
    private fun isPlaceholder(server: Server): Boolean = runCatching {
        val outbounds = org.json.JSONObject(server.xrayJson).optJSONArray("outbounds") ?: return false
        val ob = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == server.proxyTag } ?: return false
        val settings = ob.optJSONObject("settings") ?: return false
        val target = settings.optJSONArray("vnext")?.optJSONObject(0) ?: settings.optJSONArray("servers")?.optJSONObject(0)
            ?: return false
        val address = target.optString("address").lowercase()
        address in setOf("127.0.0.1", "0.0.0.0", "localhost", "::1") || (target.has("port") && target.optInt("port") <= 1)
    }.getOrDefault(false)

    /** Leading flag emoji (two regional indicators) and the rest of the name. */
    fun splitFlag(name: String): Pair<String?, String> {
        val t = name.trim()
        val cps = t.codePoints().toArray()
        if (cps.size >= 2 && cps[0] in REGIONAL && cps[1] in REGIONAL) {
            val flag = String(cps, 0, 2)
            return flag to t.substring(flag.length).trim()
        }
        return null to t
    }

    private val REGIONAL = 0x1F1E6..0x1F1FF
}
