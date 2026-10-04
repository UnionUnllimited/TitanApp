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
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes

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
