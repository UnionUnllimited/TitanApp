package com.titanvps.desktop

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Same rules as the Android app (hosts, headers, Страны / Обходы). */
object Config {
    val HOSTS = setOf("api1.titanvps.su", "api2.titanvps.online")
    const val TELEGRAM_URL = "https://t.me/TitanVPS_bot"
    /** Our User-Agent everywhere: "Titan(Windows/<version>)" (Android and iOS the same way). */
    val TITAN_UA = "Titan(Windows/${System.getProperty("jpackage.app-version") ?: "dev"})"
    /** Our own UA first; "Xray" if the server doesn't answer it (same as Android). */
    val USER_AGENTS = listOf(TITAN_UA, "Xray")
}

data class SubscriptionInfo(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    /** 0 means unlimited. */
    val totalBytes: Long = 0,
    /** Unix seconds, 0 means no expiry. */
    val expireAt: Long = 0,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val announce: String? = null,
    /** `profile-update-interval`: refreshed in the background once it is this old (as on Android). */
    val updateIntervalHours: Int = DEFAULT_UPDATE_HOURS,
    /** `sniffing-enable`: false turns the core's sniffing off; null (no header) or true: on, as always. */
    val sniffing: Boolean? = null,
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)

    companion object {
        const val DEFAULT_UPDATE_HOURS = 12
    }
}

/** One location. [xrayJson] is a full Xray config without inbounds. */
data class Server(val id: String, val name: String, val xrayJson: String, val proxyTag: String)

data class Subscription(val url: String, val info: SubscriptionInfo, val servers: List<Server>, val fetchedAt: Long)

class SubscriptionException(message: String) : Exception(message)

object SubscriptionHeaders {
    fun parse(header: (String) -> String?): SubscriptionInfo {
        val usage = parseUserInfo(header("subscription-userinfo"))
        return SubscriptionInfo(
            uploadBytes = usage["upload"] ?: 0,
            downloadBytes = usage["download"] ?: 0,
            totalBytes = usage["total"] ?: 0,
            expireAt = usage["expire"] ?: 0,
            supportUrl = header("support-url")?.trim()?.takeIf { it.startsWith("http") || it.startsWith("tg:") },
            webPageUrl = header("profile-web-page-url")?.trim()?.takeIf { it.startsWith("http") },
            announce = decodeText(header("announce")),
            updateIntervalHours = header("profile-update-interval")?.trim()?.toIntOrNull()
                ?.coerceIn(1, 24 * 7) ?: SubscriptionInfo.DEFAULT_UPDATE_HOURS,
            sniffing = parseSwitch(header("sniffing-enable")),
        )
    }

    /** `1` / `true` / `on` and `0` / `false` / `off` (any case); anything else or none: null. Android's. */
    fun parseSwitch(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "1", "true", "on" -> true
        "0", "false", "off" -> false
        else -> null
    }

    private fun parseUserInfo(value: String?): Map<String, Long> {
        if (value.isNullOrBlank()) return emptyMap()
        return value.split(';').mapNotNull { part ->
            val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val number = v.trim().toBigDecimalOrNull()?.toLong() ?: return@mapNotNull null
            k.trim().lowercase() to number
        }.toMap()
    }

    private fun decodeText(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!v.startsWith("base64:")) return v
        return runCatching { String(Base64.getDecoder().decode(v.removePrefix("base64:").trim()), Charsets.UTF_8) }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

object Links {
    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.path.isNullOrEmpty() || uri.path == "/") return false
        return host in Config.HOSTS
    }

    private val pattern = Regex(
        "https://(" + Config.HOSTS.joinToString("|") { Regex.escape(it) } + ")/[^\\s\"'<>\\\\`]+",
        RegexOption.IGNORE_CASE,
    )

    /** Our subscription URL inside pasted text (plain, deep links, percent-encoded). */
    fun find(text: String): String? {
        val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
        for (t in listOf(text, decoded)) {
            for (m in pattern.findAll(t)) {
                val url = m.value.trimEnd('.', ',', ')', ';', '&')
                if (isAllowed(url)) return url
            }
        }
        return null
    }

    /** Same path on the other allowed hosts (api1 ↔ api2). */
    fun alternates(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val host = uri.host?.lowercase() ?: return emptyList()
        return Config.HOSTS.filter { it != host }.map { url.replaceFirst(uri.rawAuthority, it) }
    }
}

object ServerGroups {
    /**
     * A tab of Главная. [networks]: where its servers work, shown with the title; [spokenNetworks]:
     * the same for screen readers. Never saved (Главная opens on the selected server's tab).
     */
    enum class Group(val title: String, val networks: String, val spokenNetworks: String) {
        SERVERS("Страны", "Wi‑Fi / LTE", "работают на Wi-Fi и LTE"),
        BYPASS("Обходы", "только LTE", "только LTE"),
    }

    /** Panel notices sent as hosts with a real address ("СЕРВЕР ДЛЯ / ОБНОВЛЕНИЯ ПОДПИСКИ"). */
    private val NOTICES = listOf("сервер для", "обновления подписки")

    /**
     * The panel's «👇БЕЗЛИМИТНЫЕ👇» row is a working config (a balancer over every regular
     * server, YouTube through its own nodes); it is shown under this name everywhere (Android, iPhone).
     */
    const val UNLIMITED_NAME = "⚡ Безлимитные · все серверы"

    /** Its id: the same after every refresh, wherever the panel puts the row. */
    const val UNLIMITED_ID = "json-unlimited"

    /**
     * Section headers ("👇БЕЗЛИМИТНЫЕ👇" without a real config, "ЛИМИТНЫЕ ОСТ: …") and info entries
     * (dummy address or notice text).
     */
    fun isHidden(server: Server): Boolean {
        val n = server.name.lowercase()
        val header = "лимитн" in n && !isUnlimited(server)
        return header || NOTICES.any { it in n } || isPlaceholder(server)
    }

    /**
     * The «👇БЕЗЛИМИТНЫЕ👇» row as the panel sends it (any emoji or flag round the word) with a real
     * address: the config, not just a header. Checked once, when the subscription is parsed
     * (XrayConfigs renames it and gives it [UNLIMITED_ID]). Android's rule.
     */
    fun isUnlimitedRow(server: Server): Boolean {
        val n = server.name.lowercase()
        return "безлимитн" in n && "обход" !in n && NOTICES.none { it in n } && hasRealAddress(server)
    }

    /** «⚡ Безлимитные · все серверы» (renamed so on parsing). */
    fun isUnlimited(server: Server): Boolean = server.name.trim() == UNLIMITED_NAME

    /**
     * Its place in the list (as on iPhone): right after the first regular АВТО, else first. АВТО
     * stays the pick for a new key.
     */
    fun placeUnlimited(servers: List<Server>): List<Server> {
        val row = servers.firstOrNull { it.id == UNLIMITED_ID } ?: return servers
        val rest = servers.filter { it.id != UNLIMITED_ID }.toMutableList()
        val auto = rest.indexOfFirst { "авто" in it.name.lowercase() && !isBypass(it) }
        rest.add(auto + 1, row)
        return rest
    }

    /**
     * Where to go from a bypass server on normal internet: the regular АВТО, else «Безлимитные ·
     * все серверы» (their balancers pick a live server, as Android's regularServer), else the
     * regular server with the best last ping (the first one without any).
     */
    fun regularFallback(servers: List<Server>, ping: (String) -> Long?): Server? {
        val regular = servers.filterNot(::isBypass)
        return regular.firstOrNull { "авто" in it.name.lowercase() }
            ?: regular.firstOrNull(::isUnlimited)
            ?: regular.minByOrNull { s -> ping(s.id)?.takeIf { it >= 0 } ?: Long.MAX_VALUE }
    }

    /** "Обходы" are exactly the servers with "обход" in the name (same as on Android). */
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

    fun split(servers: List<Server>): Map<Group, List<Server>> {
        val (bypass, regular) = servers.partition(::isBypass)
        return mapOf(Group.SERVERS to regular, Group.BYPASS to bypass).filterValues { it.isNotEmpty() }
    }

    fun groupOf(server: Server, @Suppress("UNUSED_PARAMETER") all: List<Server> = emptyList()): Group =
        if (isBypass(server)) Group.BYPASS else Group.SERVERS

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

    /** "🇳🇱" → "nl", for flag images (Windows has no flag emoji). */
    fun countryCode(flag: String?): String? {
        val cps = flag?.codePoints()?.toArray() ?: return null
        if (cps.size != 2) return null
        return cps.map { (it - 0x1F1E6 + 'a'.code).toChar() }.joinToString("")
    }

    private val REGIONAL = 0x1F1E6..0x1F1FF
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1) "%.1f ГБ".format(gb) else "%.0f МБ".format(bytes / 1024.0 / 1024.0)
}
