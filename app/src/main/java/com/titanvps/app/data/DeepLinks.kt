package com.titanvps.app.data

import java.net.URI
import java.net.URLDecoder

/**
 * Turns an incoming link into a subscription URL, accepting only our own hosts.
 *
 * Supported forms:
 *  - `titanvps://import/https://sub.host/abc`         (Happ-style, url appended)
 *  - `titanvps://import?url=https%3A%2F%2Fsub.host%2Fabc`
 *  - `https://sub.host/abc`                            (Android App Link)
 */
class DeepLinks(private val allowedHosts: Set<String>) {

    fun extractSubscriptionUrl(link: String): String? {
        val raw = link.trim()
        val candidate = when {
            raw.startsWith("titanvps://", ignoreCase = true) -> fromCustomScheme(raw)
            else -> raw
        } ?: return null
        return candidate.takeIf { isAllowed(it) }
    }

    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        // Require at least a token in the path; the bare domain is not a subscription.
        if (uri.path.isNullOrEmpty() || uri.path == "/") return false
        return host in allowedHosts
    }

    private fun fromCustomScheme(raw: String): String? {
        val rest = raw.substringAfter("://")
        val action = rest.substringBefore('/').substringBefore('?').lowercase()
        if (action != "import" && action != "add") return null
        val query = rest.substringAfter('?', "")
        queryParam(query, "url")?.let { return it }
        val tail = rest.substring(action.length).removePrefix("/")
        if (tail.isEmpty() || tail.startsWith("?")) return null
        return if (tail.startsWith("https%3A", ignoreCase = true)) decode(tail) else tail
    }

    private fun queryParam(query: String, name: String): String? =
        query.split('&').firstNotNullOfOrNull { pair ->
            val (k, v) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
            if (k == name) decode(v) else null
        }

    private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")

    companion object {
        fun parseHosts(csv: String): Set<String> =
            csv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }
}
