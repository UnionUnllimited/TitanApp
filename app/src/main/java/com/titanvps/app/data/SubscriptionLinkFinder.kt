package com.titanvps.app.data

import java.net.URLDecoder

/**
 * Finds our subscription URL inside arbitrary text (page HTML, hrefs, app deep links
 * like `happ://add/https://…` or percent-encoded variants). Only allowed hosts match.
 */
class SubscriptionLinkFinder(private val deepLinks: DeepLinks, hosts: Set<String>) {

    private val pattern = Regex(
        "https://(" + hosts.joinToString("|") { Regex.escape(it) } + ")/[^\\s\"'<>\\\\`]+",
        RegexOption.IGNORE_CASE,
    )

    fun find(text: String): String? {
        if (text.isEmpty()) return null
        val candidates = sequenceOf(text, decode(text))
        for (t in candidates) {
            for (m in pattern.findAll(t)) {
                val url = m.value.trimEnd('.', ',', ')', ';', '&')
                if (deepLinks.isAllowed(url)) return url
            }
        }
        return null
    }

    private fun decode(s: String): String =
        if ("%3A%2F%2F" in s || "%3a%2f%2f" in s) runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s) else s
}
