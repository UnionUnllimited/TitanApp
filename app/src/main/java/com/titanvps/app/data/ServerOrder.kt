package com.titanvps.app.data

/** The order servers are listed and stepped through (‹ › in the notification and the widget). */
object ServerOrder {

    /** Favourites (by name) first; both parts keep the subscription's order. */
    fun ordered(servers: List<Server>, favorites: Set<String>): List<Server> {
        if (favorites.isEmpty()) return servers
        val (favorite, rest) = servers.partition { it.name in favorites }
        return favorite + rest
    }

    /**
     * The server before or after [currentId] in its own tab (Серверы or Обходы), round the
     * end; null when the tab has no other. A bypass server on Wi-Fi ([wifiLocked]) or an
     * unknown one: the first (forward) or last regular server.
     */
    fun neighbor(
        servers: List<Server>,
        currentId: String?,
        forward: Boolean,
        favorites: Set<String>,
        wifiLocked: Boolean,
    ): Server? {
        val groups = ServerGroups.split(servers).toMap()
        val regular = ordered(groups[ServerGroups.Group.SERVERS].orEmpty(), favorites)
        val current = servers.firstOrNull { it.id == currentId }
        if (current == null || (wifiLocked && ServerGroups.isBypass(current))) {
            return if (forward) regular.firstOrNull() else regular.lastOrNull()
        }
        val list = ordered(groups[ServerGroups.groupOf(current, servers)].orEmpty(), favorites)
        if (list.size < 2) return null
        val index = list.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        val step = if (forward) 1 else -1
        return list[(index + step + list.size) % list.size]
    }
}
