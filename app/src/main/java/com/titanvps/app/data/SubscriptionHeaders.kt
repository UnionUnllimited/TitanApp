package com.titanvps.app.data

import java.util.Base64

/**
 * Parses the de-facto subscription headers (Remnawave / Marzban / Happ style):
 * `profile-title`, `subscription-userinfo`, `support-url`, `profile-web-page-url`,
 * `announce`, `profile-update-interval`. Text values may be prefixed with `base64:`.
 */
object SubscriptionHeaders {

    fun parse(header: (String) -> String?): SubscriptionInfo {
        val usage = parseUserInfo(header("subscription-userinfo"))
        return SubscriptionInfo(
            title = decodeText(header("profile-title")),
            uploadBytes = usage["upload"] ?: 0,
            downloadBytes = usage["download"] ?: 0,
            totalBytes = usage["total"] ?: 0,
            expireAt = usage["expire"] ?: 0,
            supportUrl = header("support-url")?.trim()?.takeIf { it.startsWith("http") || it.startsWith("tg:") },
            webPageUrl = header("profile-web-page-url")?.trim()?.takeIf { it.startsWith("http") },
            announce = decodeText(header("announce")),
            updateIntervalHours = header("profile-update-interval")?.trim()?.toIntOrNull()
                ?.coerceIn(1, 24 * 7) ?: SubscriptionInfo.DEFAULT_UPDATE_HOURS,
            deviceLimit = header("x-device-limit")?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
            devicesUsed = header("x-devices-used")?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
        )
    }

    /** `upload=1; download=2; total=3; expire=4` */
    fun parseUserInfo(value: String?): Map<String, Long> {
        if (value.isNullOrBlank()) return emptyMap()
        return value.split(';').mapNotNull { part ->
            val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val number = v.trim().toBigDecimalOrNull()?.toLong() ?: return@mapNotNull null
            k.trim().lowercase() to number
        }.toMap()
    }

    fun decodeText(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!v.startsWith("base64:")) return v
        return runCatching {
            String(Base64.getDecoder().decode(v.removePrefix("base64:").trim()), Charsets.UTF_8)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
