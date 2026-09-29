package com.titanvps.app.data

/** Splits locations into tabs. Bypass ("Обходы") servers are the only metered ones. */
object ServerGroups {

    enum class Group(val title: String) { SERVERS("Серверы"), BYPASS("Обходы") }

    fun groupOf(server: Server): Group =
        if (server.name.contains("обход", ignoreCase = true)) Group.BYPASS else Group.SERVERS

    /** Non-empty groups in display order. */
    fun split(servers: List<Server>): List<Pair<Group, List<Server>>> =
        Group.entries.map { g -> g to servers.filter { groupOf(it) == g } }.filter { it.second.isNotEmpty() }

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
