package com.titanvps.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The bot's public summary of a subscription (GET /api/sub/{uuid}/info) on the
 * subscription's own host: account status and devices. Same as on Android; the bot's
 * real address never appears in the app.
 */
object Account {
    data class Device(val os: String, val osVersion: String, val model: String, val app: String, val lastSeen: String)
    data class Info(val status: String, val devices: List<Device>, val deviceLimit: Int, val devicesEnabled: Boolean)

    fun uuidOf(url: String): String? =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").findAll(url).lastOrNull()?.value

    /** [proxyPort]: our local HTTP inbound when connected (0 = direct only). */
    fun fetch(subscriptionUrl: String, proxyPort: Int): Info? {
        val uuid = uuidOf(subscriptionUrl) ?: return null
        val hosts = (listOfNotNull(runCatching { java.net.URI(subscriptionUrl).host }.getOrNull()) + Config.HOSTS).distinct()
        val clients = listOfNotNull(
            client(Proxy.NO_PROXY),
            proxyPort.takeIf { it > 0 }?.let { client(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it))) },
        )
        var last: Exception? = null
        for (host in hosts) for (c in clients) {
            try {
                c.newCall(Request.Builder().url("https://$host/api/sub/$uuid/info").header("User-Agent", "Titan").build()).execute().use { r ->
                    if (r.code == 404) return null
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    return parse(JSONObject(r.body.string()))
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("unavailable")
    }

    private fun client(proxy: Proxy) = OkHttpClient.Builder().proxy(proxy)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    fun parse(o: JSONObject): Info {
        val d = o.optJSONObject("devices")
        val items = d?.optJSONArray("items")
        val devices = (0 until (items?.length() ?: 0)).mapNotNull { items!!.optJSONObject(it) }.map {
            Device(it.optString("os"), it.optString("osVersion"), it.optString("model"), it.optString("userAgent"), it.optString("accessTime"))
        }
        return Info(
            status = o.optString("userStatus", "ACTIVE"),
            devices = devices,
            deviceLimit = d?.optInt("limit") ?: 0,
            devicesEnabled = d?.optBoolean("enabled", false) ?: false,
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

    fun lastSeen(iso: String): String? {
        if (iso.isBlank()) return null
        val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso.replace(' ', 'T')).atZone(ZoneId.of("UTC")).toInstant() }.getOrNull()
            ?: return null
        val min = Duration.between(instant, Instant.now()).toMinutes().coerceAtLeast(0)
        return when {
            min < 2 -> "только что"
            min < 60 -> "$min мин назад"
            min < 60 * 24 -> "${min / 60} ч назад"
            else -> "${min / 60 / 24} дн. назад"
        }
    }
}

/**
 * Start with Windows. TUN needs admin, so when the app runs elevated a logon task
 * "with highest privileges" is used (no UAC prompt at logon); otherwise the Run key.
 */
object Autostart {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Titan VPS"

    private val exe: String?
        get() = ProcessHandle.current().info().command().orElse(null)
            ?.takeIf { it.endsWith(".exe", true) && !it.endsWith("java.exe", true) }

    val isEnabled: Boolean
        get() = isWindows && (runKey() != null || taskExists())

    fun set(enabled: Boolean, elevated: Boolean) {
        if (!isWindows) return
        val path = exe ?: return
        runCatching { com.sun.jna.platform.win32.Advapi32Util.registryDeleteValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME) }
        if (taskExists()) runCatching { exec("schtasks", "/delete", "/tn", NAME, "/f") }
        if (!enabled) return
        val arg = "\"$path\" --autostart"
        if (elevated) {
            exec("schtasks", "/create", "/tn", NAME, "/tr", arg, "/sc", "onlogon", "/rl", "highest", "/f")
        } else {
            com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME, arg)
        }
    }

    private fun runKey(): String? = runCatching {
        com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)
    }.getOrNull()

    private fun taskExists(): Boolean = runCatching { exec("schtasks", "/query", "/tn", NAME) == 0 }.getOrDefault(false)

    private fun exec(vararg cmd: String): Int =
        ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
}
