package com.titanvps.app.data

/** Splits locations into tabs. Bypass ("Обходы") servers are the only metered ones. */
object ServerGroups {

    /**
     * A tab of Главная. [networks]: where its servers work, the line under the title;
     * [spokenNetworks]: the same for screen readers. Saved by name (SERVERS), not by title.
     */
    enum class Group(val title: String, val networks: String, val spokenNetworks: String) {
        SERVERS("Страны", "Wi‑Fi / LTE", "работают на Wi-Fi и LTE"),
        BYPASS("Обходы", "только LTE", "только LTE"),
    }

    /** What a screen reader says for a tab: «Страны, 29 серверов, работают на Wi-Fi и LTE». */
    fun tabDescription(group: Group, count: Int): String =
        "${group.title}, $count ${Fmt.serversWord(count)}, ${group.spokenNetworks}"

    /**
     * The panel's «👇БЕЗЛИМИТНЫЕ👇» row is a working config (a balancer over every regular
     * server, YouTube through its own nodes); it is shown under this name everywhere.
     */
    const val UNLIMITED_NAME = "⚡ Безлимитные · все серверы"

    /** What screen readers say for its bolt. */
    const val UNLIMITED_LABEL = "Безлимитные, все серверы"

    /**
     * Not servers: section headers ("👇БЕЗЛИМИТНЫЕ👇" without a real config, "ЛИМИТНЫЕ ОСТ: …")
     * and info entries the panel sends with a dummy address ("СЕРВЕР ДЛЯ", "ОБНОВЛЕНИЯ ПОДПИСКИ", …).
     */
    fun isHidden(server: Server): Boolean {
        val n = server.name.lowercase()
        val header = "лимитн" in n && !isUnlimited(server)
        return header || NOTICES.any { it in n } || isPlaceholder(server)
    }

    /** Notices that come with a real address, so the dummy-address check misses them. */
    private val NOTICES = listOf("сервер для", "обновления подписки")

    /**
     * The «👇БЕЗЛИМИТНЫЕ👇» row as the panel sends it (any emoji or flag round the word) with a
     * real address: the config, not just a header. Checked once, when the subscription is parsed
     * (XrayConfigs renames it, gives it a fixed id and puts it first).
     */
    fun isUnlimitedRow(server: Server): Boolean {
        val n = server.name.lowercase()
        return "безлимитн" in n && "обход" !in n && NOTICES.none { it in n } && hasRealAddress(server)
    }

    /** «⚡ Безлимитные · все серверы» (renamed so on parsing). */
    fun isUnlimited(server: Server): Boolean = isUnlimitedName(server.name)

    fun isUnlimitedName(name: String): Boolean = name.trim() == UNLIMITED_NAME

    /** "Обходы" are exactly the servers with "обход" in the name (Обход 5, АВТО | Быстрые обходы). */
    fun isBypass(server: Server): Boolean = "обход" in server.name.lowercase()

    /** A bypass server that spends GB: all but the free ones ("Обход 1 бесплатный"). */
    fun isLimited(server: Server): Boolean = isBypass(server) && "бесплатн" !in letters(server.name)

    /** The name's letters only, lower case, Latin look-alikes read as Cyrillic ("бecплaтный"). */
    private fun letters(name: String): String = buildString {
        for (ch in java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC).lowercase()) {
            if (ch.isLetter()) append(LOOKALIKES[ch] ?: ch)
        }
    }

    private val LOOKALIKES = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х', 'y' to 'у',
        'k' to 'к', 'm' to 'м', 't' to 'т', 'h' to 'н', 'b' to 'в', 'n' to 'п', 'u' to 'и',
    )

    fun groupOf(server: Server, @Suppress("UNUSED_PARAMETER") all: List<Server> = emptyList()): Group =
        if (isBypass(server)) Group.BYPASS else Group.SERVERS

    /** Non-empty groups in display order; each keeps the subscription's order. */
    fun split(servers: List<Server>): List<Pair<Group, List<Server>>> {
        val (bypass, regular) = servers.partition(::isBypass)
        return listOf(Group.SERVERS to regular, Group.BYPASS to bypass).filter { it.second.isNotEmpty() }
    }

    /** The proxy outbound points nowhere (127.0.0.1, 0.0.0.0, port 0/1): an info entry. */
    private fun isPlaceholder(server: Server): Boolean = target(server, flat = false)?.let(::isDummy) ?: false

    /** The proxy outbound has an address and it is not a dummy one. */
    private fun hasRealAddress(server: Server): Boolean =
        target(server, flat = true)?.let { it.optString("address").isNotBlank() && !isDummy(it) } ?: false

    /**
     * The proxy outbound's server entry: vnext[0] / servers[0]; with [flat] also the settings
     * themselves when they carry the address (hysteria, the short vless form).
     */
    private fun target(server: Server, flat: Boolean): org.json.JSONObject? = runCatching {
        val outbounds = org.json.JSONObject(server.xrayJson).optJSONArray("outbounds") ?: return null
        val ob = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == server.proxyTag } ?: return null
        val settings = ob.optJSONObject("settings") ?: return null
        settings.optJSONArray("vnext")?.optJSONObject(0) ?: settings.optJSONArray("servers")?.optJSONObject(0)
            ?: settings.takeIf { flat && it.has("address") }
    }.getOrNull()

    private fun isDummy(target: org.json.JSONObject): Boolean {
        val address = target.optString("address").lowercase()
        return address in DUMMY_ADDRESSES || (target.has("port") && target.optInt("port") <= 1)
    }

    private val DUMMY_ADDRESSES = setOf("127.0.0.1", "0.0.0.0", "localhost", "::1")

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
