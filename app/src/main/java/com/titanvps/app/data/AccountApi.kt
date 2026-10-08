package com.titanvps.app.data

import com.titanvps.app.BuildConfig
import com.titanvps.app.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The bot's public summary of a subscription (GET /api/sub/{uuid}/info): account
 * status and the devices that used the key. No secrets — the key's UUID is the access.
 *
 * Asked on the subscription's own host (api1/api2), whose nginx forwards only this path
 * to the bot: the bot's real domain and IP never appear in the app or its traffic.
 */
object AccountApi {

    data class Device(
        val os: String,
        val osVersion: String,
        val model: String,
        val app: String,
        val lastSeen: String,
        val firstSeen: String,
    )

    data class Info(
        /** ACTIVE, BLOCKED, MAINTENANCE, … */
        val status: String,
        val devices: List<Device>,
        val deviceLimit: Int,
        val devicesEnabled: Boolean,
    )

    /** "…/sub/<uuid>" or "…/<uuid>" → uuid. */
    fun uuidOf(subscriptionUrl: String): String? =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            .findAll(subscriptionUrl).lastOrNull()?.value
            ?: subscriptionUrl.trimEnd('/').substringAfterLast('/').takeIf { it.length >= 8 }

    suspend fun fetch(subscriptionUrl: String): Info? = withContext(Dispatchers.IO) {
        val uuid = uuidOf(subscriptionUrl) ?: return@withContext null
        // The subscription's own host first, then the other one (api1 ↔ api2).
        val hosts = (listOfNotNull(runCatching { java.net.URI(subscriptionUrl).host }.getOrNull()) +
            BuildConfig.SUB_HOSTS.split(',').map { it.trim() }).filter { it.isNotEmpty() }.distinct()
        // Direct first (our app bypasses the tunnel); through the VPN if that's blocked.
        val clients = listOfNotNull(direct, viaVpn())
        var last: Exception? = null
        for (host in hosts) for (client in clients) {
            val url = "https://$host/api/sub/$uuid/info"
            try {
                client.newCall(Request.Builder().url(url).header("User-Agent", "Titan").build()).execute().use { r ->
                    if (r.code == 404) return@withContext null
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    return@withContext parse(JSONObject(r.body.string()))
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("unavailable")
    }

    private val direct = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun viaVpn(): OkHttpClient? {
        val lp = VpnStatus.localProxy ?: return null
        return direct.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", lp.port)))
            .proxyAuthenticator { _, response ->
                response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(lp.user, lp.password)).build()
            }
            .build()
    }

    internal fun parse(o: JSONObject): Info {
        val devicesObj = o.optJSONObject("devices")
        val items = devicesObj?.optJSONArray("items")
        val devices = (0 until (items?.length() ?: 0)).mapNotNull { items!!.optJSONObject(it) }.map { d ->
            Device(
                os = d.optString("os"),
                osVersion = d.optString("osVersion"),
                model = d.optString("model"),
                app = d.optString("userAgent"),
                lastSeen = d.optString("accessTime"),
                firstSeen = d.optString("createdAt"),
            )
        }
        return Info(
            status = o.optString("userStatus", "ACTIVE"),
            devices = devices,
            deviceLimit = devicesObj?.optInt("limit") ?: o.optJSONObject("subscription")?.optInt("limitIp") ?: 0,
            devicesEnabled = devicesObj?.optBoolean("enabled", false) ?: false,
        )
    }

    /** "Happ/5.9.0/ios/…" → "Happ 5.9.0"; our own UAs → "Titan VPS". */
    fun appName(userAgent: String): String {
        val parts = userAgent.split('/')
        val name = parts.firstOrNull().orEmpty().trim()
        return when {
            name.equals("Titan", true) -> "Titan VPS"
            name.isEmpty() -> "Неизвестное приложение"
            parts.size > 1 && parts[1].firstOrNull()?.isDigit() == true -> "$name ${parts[1]}"
            else -> name
        }
    }
}
