package com.titanvps.app.data

/** Metadata Remnawave sends in subscription response headers. */
data class SubscriptionInfo(
    val title: String? = null,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    /** 0 means unlimited. */
    val totalBytes: Long = 0,
    /** Unix seconds, 0 means no expiry. */
    val expireAt: Long = 0,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val announce: String? = null,
    val updateIntervalHours: Int = DEFAULT_UPDATE_HOURS,
    /** Device (HWID) limit from `x-device-limit`; 0 = unlimited, null = not sent. */
    val deviceLimit: Int? = null,
    /** Devices already bound, from `x-devices-used`. */
    val devicesUsed: Int? = null,
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes

    /** "2 из 3", "Без лимита", or null when the server doesn't send it. */
    val devicesText: String?
        get() = when {
            deviceLimit == null -> null
            deviceLimit == 0 -> devicesUsed?.let { "$it · без лимита" } ?: "Без лимита"
            else -> "${devicesUsed ?: "?"} из $deviceLimit"
        }

    companion object {
        const val DEFAULT_UPDATE_HOURS = 12
    }
}

/** One selectable location. [xrayJson] is a complete Xray config without inbounds. */
data class Server(
    val id: String,
    val name: String,
    val xrayJson: String,
    /** Tag of the outbound that carries user traffic (used for ping). */
    val proxyTag: String,
)

data class Subscription(
    val url: String,
    val info: SubscriptionInfo,
    val servers: List<Server>,
    val fetchedAt: Long,
)
