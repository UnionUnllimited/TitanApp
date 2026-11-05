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
                c.newCall(Request.Builder().url("https://$host/api/sub/$uuid/info").header("User-Agent", Config.TITAN_UA).build()).execute().use { r ->
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
        // Our own: "Titan(Android/1.4.2)" → "Titan VPS · Android 1.4.2".
        Regex("""^Titan\(([^/)]+)/([^)]*)\)""").find(userAgent)?.let { m ->
            val platform = mapOf("ios" to "iOS", "android" to "Android", "windows" to "Windows")[m.groupValues[1].lowercase()] ?: m.groupValues[1]
            return "Titan VPS · $platform" + m.groupValues[2].let { if (it.isEmpty()) "" else " $it" }
        }
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
    /** Task Manager's and Settings' «Автозагрузка» switch for each Run key value. */
    private const val APPROVED_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
    private const val NAME = "Titan VPS"

    private val exe: String?
        get() = ProcessHandle.current().info().command().orElse(null)
            ?.takeIf { it.endsWith(".exe", true) && !it.endsWith("java.exe", true) }

    val isEnabled: Boolean
        get() = isWindows && (viaRunKey || taskExists())

    /**
     * On through the Run key (turned on without administrator rights): it starts us without
     * them. Not when turned off in Task Manager or Settings > Apps > Startup: Windows keeps the
     * value then and skips it, so it is off.
     */
    val viaRunKey: Boolean
        get() = isWindows && runKey() != null && !offInWindows(approved())

    /**
     * Throws if Windows refused (the toggle says so then): also the logon task made with
     * administrator rights, which a copy without them can't delete.
     */
    fun set(enabled: Boolean, elevated: Boolean) {
        if (!isWindows) return
        val path = exe ?: return
        if (enabled && elevated) {
            // The task first: if Windows refuses it, the Run key stays and nothing is lost.
            createTask(path)
            deleteRunKey()
            return
        }
        if (taskExists()) check(exec("schtasks", "/delete", "/tn", NAME, "/f") == 0) { "schtasks /delete failed" }
        deleteRunKey()
        if (!enabled) return
        com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME, "\"$path\" --autostart")
    }

    /** The Run key value and Windows' own switch for it: one turned on here again isn't off there. */
    private fun deleteRunKey() {
        for (key in listOf(RUN_KEY, APPROVED_KEY)) {
            runCatching { com.sun.jna.platform.win32.Advapi32Util.registryDeleteValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, key, NAME) }
        }
    }

    /**
     * A StartupApproved\Run value: Windows' switch, its first byte odd when turned off there
     * (03, 07), even when on (02, 06); none counts as on.
     */
    fun offInWindows(approved: ByteArray?): Boolean = approved != null && approved.isNotEmpty() && (approved[0].toInt() and 1) == 1

    private fun approved(): ByteArray? = runCatching {
        com.sun.jna.platform.win32.Advapi32Util.registryGetBinaryValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, APPROVED_KEY, NAME)
    }.getOrNull()

    /**
     * The logon task made by an earlier version (schtasks' defaults: no start on battery,
     * stopped when the charger is pulled and after 72 hours, the cores at below-normal
     * priority) gets [taskXml]'s settings. Only with administrator rights, as the task has them;
     * if Windows refuses, the old task stays.
     */
    fun repair() {
        if (!isWindows || !Admin.isAdmin) return
        val path = exe ?: return
        val xml = queryTaskXml() ?: return
        if (!isCurrent(xml)) runCatching { createTask(path) }
    }

    /**
     * The logon task: at logon, with the highest rights, also on battery and never stopped by
     * the Task Scheduler (no time limit), at normal priority (the cores inherit it).
     */
    fun taskXml(exe: String): String {
        val command = "\"$exe\"".replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>4</Priority>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>$command</Command>
      <Arguments>--autostart</Arguments>
    </Exec>
  </Actions>
</Task>
"""
    }

    /** [xml] (the task as `schtasks /query /xml` prints it) already has [taskXml]'s settings. */
    fun isCurrent(xml: String): Boolean {
        val flat = xml.filterNot { it.isWhitespace() }
        return CURRENT.all { it in flat }
    }

    private val CURRENT = listOf(
        "<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>",
        "<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>",
        "<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>",
        "<Priority>4</Priority>",
    )

    private fun createTask(path: String) {
        val file = java.io.File.createTempFile("titan-autostart", ".xml")
        try {
            // UTF-16 with its mark, as the declaration says (what schtasks itself writes).
            file.writeBytes(("\uFEFF" + taskXml(path)).toByteArray(Charsets.UTF_16LE))
            check(exec("schtasks", "/create", "/tn", NAME, "/xml", file.absolutePath, "/f") == 0) { "schtasks /create failed" }
        } finally {
            file.delete()
        }
    }

    /** The task's definition, null if there is none. */
    private fun queryTaskXml(): String? = runCatching {
        val p = ProcessBuilder("schtasks", "/query", "/tn", NAME, "/xml").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.use { it.readBytes() }
        if (p.waitFor() != 0) null else textOf(out)
    }.getOrNull()

    /**
     * schtasks' output as text for [isCurrent], whether it came as UTF-16 or in the console's
     * code page: only its ASCII matters, so it is read byte by byte with UTF-16's zero bytes dropped.
     */
    fun textOf(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1).replace("\u0000", "")

    private fun runKey(): String? = runCatching {
        com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, RUN_KEY, NAME)
    }.getOrNull()

    private fun taskExists(): Boolean = runCatching { exec("schtasks", "/query", "/tn", NAME) == 0 }.getOrDefault(false)

    private fun exec(vararg cmd: String): Int =
        ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
}
