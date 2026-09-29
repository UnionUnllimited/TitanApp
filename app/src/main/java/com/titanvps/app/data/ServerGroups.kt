package com.titanvps.app.data

/** Splits locations into tabs. Bypass ("Обходы") servers are the only metered ones. */
object ServerGroups {

    enum class Group(val title: String) { SERVERS("Серверы"), BYPASS("Обходы") }

    /** Start of the bypass section: its "ЛИМИТНЫЕ …" header or the first "обход" server. */
    private fun isBypassName(server: Server): Boolean {
        val n = server.name.lowercase()
        return "обход" in n || ("лимитн" in n && "безлимит" !in n)
    }

    /**
     * The subscription lists regular servers first, then the bypass section. Everything
     * from its start (the "ЛИМИТНЫЕ" header, its own АВТО, info entries, …) is the bypass tab.
     */
    fun groupOf(server: Server, all: List<Server>): Group {
        val first = all.indexOfFirst(::isBypassName)
        val index = all.indexOfFirst { it.id == server.id }
        return if (first >= 0 && index >= first) Group.BYPASS else Group.SERVERS
    }

    /** Non-empty groups in display order. */
    fun split(servers: List<Server>): List<Pair<Group, List<Server>>> {
        val first = servers.indexOfFirst(::isBypassName)
        val bypass = if (first >= 0) servers.drop(first) else emptyList()
        val regular = if (first >= 0) servers.take(first) else servers
        return listOf(Group.SERVERS to regular, Group.BYPASS to bypass).filter { it.second.isNotEmpty() }
    }

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
