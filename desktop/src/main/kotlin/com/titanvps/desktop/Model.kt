package com.titanvps.desktop

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Same rules as the Android app (hosts, headers, Серверы / Обходы). */
object Config {
    val HOSTS = setOf("api1.titanvps.su", "api2.titanvps.online")
    const val TELEGRAM_URL = "https://t.me/TitanVPS_bot"
    /** Our own UA first; "Xray" if the server doesn't answer it (same as Android). */
    val USER_AGENTS = listOf("Titan", "Xray")
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
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
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
        )
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
    enum class Group(val title: String) { SERVERS("Серверы"), BYPASS("Обходы") }

    fun isHidden(server: Server): Boolean = "безлимит" in server.name.lowercase()

    private fun isBypassName(server: Server): Boolean {
        val n = server.name.lowercase()
        return "обход" in n || ("лимитн" in n && "безлимит" !in n)
    }

    fun split(servers: List<Server>): Map<Group, List<Server>> {
        val first = servers.indexOfFirst(::isBypassName)
        return if (first < 0) mapOf(Group.SERVERS to servers)
        else mapOf(Group.SERVERS to servers.take(first), Group.BYPASS to servers.drop(first)).filterValues { it.isNotEmpty() }
    }

    fun groupOf(server: Server, all: List<Server>): Group {
        val first = all.indexOfFirst(::isBypassName)
        val index = all.indexOfFirst { it.id == server.id }
        return if (first >= 0 && index >= first) Group.BYPASS else Group.SERVERS
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
