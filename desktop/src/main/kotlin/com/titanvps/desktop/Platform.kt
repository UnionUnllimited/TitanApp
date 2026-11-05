package com.titanvps.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket

val isWindows = System.getProperty("os.name").lowercase().contains("win")

object AppPaths {
    /** %APPDATA%\TitanVPS — settings, subscription, logs, trimmed geo files. */
    val dataDir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "TitanVPS").apply { mkdirs() }
    val logDir: File = File(dataDir, "logs").apply { mkdirs() }

    /** Folder with xray.exe and the geo files (packaged app resources, or TITAN_XRAY_DIR for dev runs). */
    val coreDir: File
        get() = System.getProperty("compose.application.resources.dir")?.let(::File)
            ?: System.getenv("TITAN_XRAY_DIR")?.let(::File)
            ?: File("resources/windows")

    val xrayExe: File get() = File(coreDir, if (isWindows) "xray.exe" else "xray")
}

fun freePorts(count: Int): List<Int> {
    val sockets = List(count) { ServerSocket(0) }
    return sockets.map { it.localPort }.also { sockets.forEach(ServerSocket::close) }
}

/**
 * [text] as a PowerShell single-quoted string: its quote marks doubled, the curly ones too, which
 * PowerShell also takes for quotes (a profile folder like O'Brien or O’Brien).
 */
fun psQuoted(text: String): String =
    "'" + PS_QUOTES.fold(text) { s, q -> s.replace(q.toString(), "$q$q") } + "'"

private const val PS_QUOTES = "'\u2018\u2019\u201A\u201B"

/** Device id for the subscription's HWID limit: the Windows MachineGuid. */
object Hwid {
    fun get(): String {
        if (isWindows) runCatching {
            return Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Cryptography", "MachineGuid")
        }
        val f = File(AppPaths.dataDir, "hwid")
        if (!f.exists()) f.writeText(java.util.UUID.randomUUID().toString())
        return f.readText().trim()
    }

    fun model(): String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("PC")
}

/**
 * Windows system proxy (Internet Options, used by browsers and most apps). The previous
 * settings are saved to a file first, so they come back even after a crash.
 */
object SystemProxy {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    private val backup by lazy { File(AppPaths.dataDir, "proxy-backup.json") }
    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;" +
        "172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    // wininet.h: the per-connection options and the kinds of proxy a connection uses.
    private const val INTERNET_OPTION_PER_CONNECTION_OPTION = 75
    private const val PER_CONN_FLAGS = 1
    private const val PER_CONN_PROXY_SERVER = 2
    private const val PER_CONN_PROXY_BYPASS = 3
    private const val PER_CONN_AUTOCONFIG_URL = 4
    /** The flags as the settings window shows them (the user's choice); not on Windows before IE 8. */
    private const val PER_CONN_FLAGS_UI = 10
    const val PROXY_TYPE_DIRECT = 1
    const val PROXY_TYPE_PROXY = 2
    const val PROXY_TYPE_AUTO_PROXY_URL = 4
    const val PROXY_TYPE_AUTO_DETECT = 8

    // ras.h / raserror.h
    private const val ERROR_BUFFER_TOO_SMALL = 603
    private const val ERROR_INVALID_SIZE = 632

    /**
     * RASENTRYNAMEW sizes, newest first: dwSize, WCHAR[257] name, dwFlags, WCHAR[261]
     * phonebook path, 4-byte aligned (1048, since Windows 2000); before that dwSize and the
     * name only (520). The name is at offset 4 in both.
     */
    val RAS_ENTRY_SIZES = listOf(1048, 520)

    private interface WinInet : Library {
        fun InternetSetOptionW(hInternet: Pointer?, option: Int, buffer: Pointer?, length: Int): Boolean
        fun InternetQueryOptionW(hInternet: Pointer?, option: Int, buffer: Pointer?, length: IntByReference): Boolean
    }

    private interface RasApi : Library {
        fun RasEnumEntriesW(reserved: Pointer?, phonebook: Pointer?, entries: Pointer, size: IntByReference, count: IntByReference): Int
    }

    /** One connection's proxy settings as WinINet keeps them: [flags] PROXY_TYPE_*, the proxy, its exceptions, the setup script. */
    data class Settings(val flags: Int, val server: String, val bypass: String, val pac: String)

    fun enable(httpPort: Int) {
        if (!isWindows) return
        val fresh = !backup.exists()
        // Unreadable: kept as it is (it may hold what was there before us), and the dial-up
        // and VPN connections are left alone, as restore() couldn't put them back.
        val saved = if (fresh) {
            backupOf(readInt("ProxyEnable"), readString("ProxyServer"), readString("ProxyOverride"), readString("AutoConfigURL"), connectionFlags())
        } else {
            runCatching { JSONObject(backup.readText()) }.getOrNull()
        }
        // A dial-up (PPPoE) or VPN connection of Windows, while up, has its own settings, not
        // the LAN's: browsers would go around us there. Each is saved before it is changed.
        val ras = saved?.let { o -> o.optJSONArray("ras") ?: JSONArray().also { o.put("ras", it) } }
        val before = ras?.length() ?: 0
        val dialUp = if (ras == null) emptyList() else takeRas(ras, rasEntries(), ::queryPerConnection)
        if (saved != null && (fresh || ras!!.length() != before)) backup.writeText(saved.toString())
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", 1)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer", "127.0.0.1:$httpPort")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride", BYPASS)
        // A setup script (PAC) or «Определять параметры автоматически» (WPAD) comes before a
        // manual proxy in browsers and Windows: their DIRECT answers would go around the VPN
        // while the app says «Подключено». Only our proxy while on (as v2rayN and Clash); both
        // come back with restore().
        deleteValue("AutoConfigURL")
        setPerConnection(null, PROXY_TYPE_DIRECT or PROXY_TYPE_PROXY, "127.0.0.1:$httpPort", BYPASS, pac = null)
        dialUp.forEach { setPerConnection(it, PROXY_TYPE_DIRECT or PROXY_TYPE_PROXY, "127.0.0.1:$httpPort", BYPASS, pac = null) }
        notifyChanged()
    }

    /** `Titan VPS.exe --restore-proxy` only runs [restore] and quits: the installer's way to it. */
    const val RESTORE_ARG = "--restore-proxy"

    /** Puts back what was there before we connected. */
    fun restore() {
        if (!isWindows || !backup.exists()) return
        val o = runCatching { JSONObject(backup.readText()) }.getOrNull()
        runCatching {
            checkNotNull(o) { "unreadable" }
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", o.optInt("enable", 0))
            val server = o.optString("server")
            if (server.isEmpty()) deleteValue("ProxyServer") else
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer", server)
            val override = o.optString("override")
            if (override.isEmpty()) deleteValue("ProxyOverride") else
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride", override)
            val pac = o.optString("pac")
            if (pac.isNotEmpty()) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "AutoConfigURL", pac)
            // Saved by enable() of this version: the connection's own kinds back too (the script,
            // automatic detection). An older backup has none: enable() didn't change them then.
            if (o.has("flags")) setPerConnection(null, o.getInt("flags"), server, override, pac.ifEmpty { null })
        }.onFailure {
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", 0)
        }
        // The dial-up and VPN connections enable() changed (none in an older backup).
        o?.let { savedRas(it) }?.forEach { (name, s) -> setPerConnection(name, s.flags, s.server, s.bypass, s.pac.ifEmpty { null }) }
        backup.delete()
        notifyChanged()
    }

    /** The LAN settings from before us, as the backup keeps them (and the installer reads them). */
    fun backupOf(enable: Int, server: String, override: String, pac: String, flags: Int?): JSONObject = JSONObject()
        .put("enable", enable)
        .put("server", server)
        .put("override", override)
        .put("pac", pac)
        .apply { flags?.let { put("flags", it) } }

    /**
     * The dial-up and VPN connections enable() points at us: those already in [ras] (the
     * backup, which holds what they had before us), then each other one of [entries] once
     * [read] gave its settings and they are added to [ras]. One that can't be read is left
     * alone, as restore() couldn't put it back. A list, and keys of their own: the installer
     * reads "enable", "server", "override" and "pac" as the first of their name in the file.
     */
    fun takeRas(ras: JSONArray, entries: List<String>, read: (String) -> Settings?): List<String> {
        val saved = (0 until ras.length()).mapNotNull { ras.optJSONObject(it)?.optString("name") }.toSet()
        return entries.distinct().filter { name ->
            name in saved || read(name)?.also { s ->
                ras.put(JSONObject().put("name", name).put("flags", s.flags).put("proxy", s.server).put("bypass", s.bypass).put("script", s.pac))
            } != null
        }
    }

    /** The dial-up and VPN connections' settings saved in a backup by enable(); none in an older one. */
    fun savedRas(backup: JSONObject): Map<String, Settings> {
        val ras = backup.optJSONArray("ras") ?: return emptyMap()
        return (0 until ras.length()).mapNotNull { i ->
            val e = ras.optJSONObject(i) ?: return@mapNotNull null
            val name = e.optString("name")
            if (name.isEmpty() || !e.has("flags")) return@mapNotNull null
            name to Settings(e.optInt("flags", PROXY_TYPE_DIRECT), e.optString("proxy"), e.optString("bypass"), e.optString("script"))
        }.toMap()
    }

    /**
     * The names in RasEnumEntriesW's buffer: [count] RASENTRYNAMEW of [entrySize] bytes, each
     * name a NUL-ended UTF-16LE string at offset 4.
     */
    fun rasNames(bytes: ByteArray, count: Int, entrySize: Int): List<String> = (0 until count).mapNotNull { i ->
        val from = i * entrySize + 4
        val end = minOf((i + 1) * entrySize, bytes.size)
        if (from >= end) return@mapNotNull null
        var to = from
        while (to + 1 < end && (bytes[to].toInt() != 0 || bytes[to + 1].toInt() != 0)) to += 2
        String(bytes, from, to - from, Charsets.UTF_16LE).takeIf { it.isNotEmpty() }
    }

    private fun readInt(name: String) = runCatching { Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, KEY, name) }.getOrDefault(0)
    private fun readString(name: String) = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, name) }.getOrDefault("")
    private fun deleteValue(name: String) = runCatching { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, name) }

    /** The kinds of proxy the LAN connection uses now (PROXY_TYPE_*), null if not known. */
    private fun connectionFlags(): Int? = runCatching {
        flagsOf(Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, "$KEY\\Connections", "DefaultConnectionSettings"))
    }.getOrNull()

    /** The flags in a DefaultConnectionSettings value: its third little-endian DWORD; null if it is shorter. */
    fun flagsOf(blob: ByteArray?): Int? {
        if (blob == null || blob.size < 12) return null
        return java.nio.ByteBuffer.wrap(blob, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    }

    /**
     * Where the fields of INTERNET_PER_CONN_OPTION_LISTW and of one INTERNET_PER_CONN_OPTIONW
     * are for a pointer of [pointerSize] bytes. An option is a DWORD and a union of a DWORD, a
     * string and a FILETIME: 16 bytes with 64-bit pointers (the union 8-aligned), 12 with 32-bit.
     * The list: dwSize, pszConnection, dwOptionCount, dwOptionError, pOptions.
     */
    data class PerConnLayout(
        val optionSize: Int, val valueOffset: Int, val listSize: Int, val connectionOffset: Int, val countOffset: Int, val optionsOffset: Int,
    ) {
        companion object {
            fun of(pointerSize: Int) = PerConnLayout(
                optionSize = if (pointerSize == 8) 16 else 12,
                valueOffset = if (pointerSize == 8) 8 else 4,
                listSize = 3 * pointerSize + 8,
                connectionOffset = pointerSize,
                countOffset = 2 * pointerSize,
                optionsOffset = 2 * pointerSize + 8,
            )
        }
    }

    /** A NUL-ended UTF-16 copy of [text] in native memory, kept in [keep] until WinINet is done with it. */
    private fun wide(text: String, keep: MutableList<Memory>) =
        Memory((text.length + 1L) * Native.WCHAR_SIZE).apply { setWideString(0, text) }.also { keep += it }

    /**
     * The per-connection option list for [connection] (null: the LAN connection) with
     * [values] (option id to an Int or a String; a null value only asks for that option).
     */
    private fun optionList(l: PerConnLayout, connection: String?, values: List<Pair<Int, Any?>>, keep: MutableList<Memory>): Pair<Memory, Memory> {
        val options = Memory(l.optionSize.toLong() * values.size).apply { clear() }
        values.forEachIndexed { i, (option, value) ->
            val at = i.toLong() * l.optionSize
            options.setInt(at, option)
            when (value) {
                is Int -> options.setInt(at + l.valueOffset, value)
                is String -> options.setPointer(at + l.valueOffset, wide(value, keep))
            }
        }
        val list = Memory(l.listSize.toLong()).apply { clear() }
        list.setInt(0, l.listSize)
        connection?.let { list.setPointer(l.connectionOffset.toLong(), wide(it, keep)) }
        list.setInt(l.countOffset.toLong(), values.size)
        list.setPointer(l.optionsOffset.toLong(), options)
        keep += options
        return list to options
    }

    /**
     * A connection's settings through WinINet itself, which keeps its own copy
     * (Connections\DefaultConnectionSettings for the LAN, [connection] null; the dial-up or VPN
     * connection's name otherwise) and the values above in step: [flags] (PROXY_TYPE_*), the
     * proxy and its exceptions, and the setup script's address unless [pac] is null. False if
     * Windows refused.
     */
    private fun setPerConnection(connection: String?, flags: Int, server: String, bypass: String, pac: String?): Boolean = runCatching {
        val l = PerConnLayout.of(Native.POINTER_SIZE)
        val keep = ArrayList<Memory>()
        val values: List<Pair<Int, Any?>> = listOfNotNull(
            PER_CONN_FLAGS to flags,
            PER_CONN_PROXY_SERVER to server,
            PER_CONN_PROXY_BYPASS to bypass,
            pac?.let { PER_CONN_AUTOCONFIG_URL to it },
        )
        val (list, _) = optionList(l, connection, values, keep)
        val ok = Native.load("wininet", WinInet::class.java).InternetSetOptionW(null, INTERNET_OPTION_PER_CONNECTION_OPTION, list, l.listSize)
        // Native memory freed only once WinINet has copied it.
        java.lang.ref.Reference.reachabilityFence(keep)
        ok
    }.getOrDefault(false)

    /**
     * A dial-up or VPN connection's settings now, null if Windows didn't say. Its flags as the
     * settings window has them ([PER_CONN_FLAGS_UI]): WinINet may clear automatic detection in
     * the other ones after it found nothing.
     */
    private fun queryPerConnection(connection: String): Settings? =
        queryPerConnection(connection, PER_CONN_FLAGS_UI) ?: queryPerConnection(connection, PER_CONN_FLAGS)

    private fun queryPerConnection(connection: String, flagsOption: Int): Settings? = runCatching {
        val l = PerConnLayout.of(Native.POINTER_SIZE)
        val keep = ArrayList<Memory>()
        val ids = listOf(flagsOption, PER_CONN_PROXY_SERVER, PER_CONN_PROXY_BYPASS, PER_CONN_AUTOCONFIG_URL)
        val (list, options) = optionList(l, connection, ids.map { it to null }, keep)
        val ok = Native.load("wininet", WinInet::class.java)
            .InternetQueryOptionW(null, INTERNET_OPTION_PER_CONNECTION_OPTION, list, IntByReference(l.listSize))
        java.lang.ref.Reference.reachabilityFence(keep)
        if (!ok) return@runCatching null
        // The strings are WinINet's (GlobalAlloc): read, then freed.
        val texts = (1 until ids.size).map { i ->
            options.getPointer(i.toLong() * l.optionSize + l.valueOffset)?.let { p ->
                try { p.getWideString(0) } finally { Kernel32.INSTANCE.GlobalFree(p) }
            }.orEmpty()
        }
        Settings(options.getInt(l.valueOffset.toLong()), texts[0], texts[1], texts[2])
    }.getOrNull()

    /** Windows' dial-up and VPN connections (PPPoE, L2TP, SSTP, IKEv2…) by name; none if Windows didn't say. */
    private fun rasEntries(): List<String> = runCatching {
        val ras = Native.load("rasapi32", RasApi::class.java)
        for (size in RAS_ENTRY_SIZES) {
            fun buffer(n: Int) = Memory(n.toLong() * size).apply {
                clear()
                for (i in 0 until n) setInt(i.toLong() * size, size)
            }
            var n = 1
            var buf = buffer(n)
            val cb = IntByReference(size)
            val count = IntByReference(0)
            var result = ras.RasEnumEntriesW(null, null, buf, cb, count)
            if (result == ERROR_BUFFER_TOO_SMALL) {
                n = (cb.value + size - 1) / size
                buf = buffer(n)
                cb.value = n * size
                result = ras.RasEnumEntriesW(null, null, buf, cb, count)
            }
            // This Windows doesn't take this size: the one before it.
            if (result == ERROR_INVALID_SIZE) continue
            if (result != 0) return@runCatching emptyList()
            val found = minOf(count.value, n)
            return@runCatching rasNames(buf.getByteArray(0, found * size), found, size)
        }
        emptyList()
    }.getOrDefault(emptyList())

    /** Tells WinINet apps (browsers, Windows itself) to re-read the proxy settings now. */
    private fun notifyChanged() {
        runCatching {
            val wininet = Native.load("wininet", WinInet::class.java)
            wininet.InternetSetOptionW(null, 39, null, 0) // INTERNET_OPTION_SETTINGS_CHANGED
            wininet.InternetSetOptionW(null, 37, null, 0) // INTERNET_OPTION_REFRESH
        }
    }
}

/** Runs xray.exe with a config file; one instance per [name]. */
class XrayProcess(private val name: String) {
    @Volatile private var process: Process? = null
    private val pidFile = File(AppPaths.dataDir, "$name.pid")
    /** What the last start ran (kept after an exit, for [restart]). */
    private var lastConfig: String? = null
    private var lastAssets: File = AppPaths.coreDir

    init {
        // A core left over from a crash would keep the ports busy.
        runCatching { pidFile.readText().trim().toLong() }.getOrNull()?.let { pid ->
            ProcessHandle.of(pid).ifPresent { if (it.info().command().orElse("").endsWith("xray.exe")) it.destroyForcibly() }
        }
        pidFile.delete()
    }

    val isRunning: Boolean get() = process?.isAlive == true

    /** Completes when the running core exits (whoever ends it); null when none runs. */
    fun exits(): java.util.concurrent.CompletableFuture<Process>? = process?.onExit()

    /** Starts the core; throws with Xray's own message if it exits right away. */
    @Synchronized
    fun start(config: String, assetDir: File = AppPaths.coreDir) {
        stop()
        lastConfig = config
        lastAssets = assetDir
        val exe = AppPaths.xrayExe
        if (!exe.exists()) throw IllegalStateException("Не найден ${exe.absolutePath}")
        val configFile = File(AppPaths.dataDir, "$name.json").apply { writeText(config) }
        val out = File(AppPaths.logDir, "$name-stdout.log")
        val p = ProcessBuilder(exe.absolutePath, "run", "-c", configFile.absolutePath)
            .directory(AppPaths.coreDir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .apply { environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath }
            .start()
        process = p
        pidFile.writeText(p.pid().toString())
        Thread.sleep(700)
        if (!p.isAlive) {
            val tail = runCatching { out.readLines().takeLast(5).joinToString("\n") }.getOrDefault("")
            process = null
            throw IllegalStateException("Ядро Xray не запустилось:\n$tail")
        }
    }

    /** The core again after it exited: the same config, so the same ports. */
    @Synchronized
    fun restart() = start(lastConfig ?: throw IllegalStateException("Ядро ещё не запускалось"), lastAssets)

    @Synchronized
    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        pidFile.delete()
    }
}

/** Windows 11 rounded corners for our frameless window (DWM; ignored on older Windows). */
object WindowCorners {
    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: com.sun.jna.ptr.IntByReference, size: Int): Int
    }

    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    /**
     * Maximized, a frameless window covers the whole screen, the taskbar too: it is kept to
     * the work area of the screen it is on (again after each move to another screen).
     */
    fun keepTaskbar(frame: java.awt.Frame) {
        fun fit() {
            val gc = frame.graphicsConfiguration ?: return
            val b = gc.bounds
            val i = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            frame.maximizedBounds = java.awt.Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
        }
        fit()
        frame.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) = fit()
        })
    }

    fun round(window: java.awt.Window) {
        if (!isWindows) return
        runCatching {
            val hwnd = Pointer(Native.getComponentID(window))
            Native.load("dwmapi", Dwm::class.java)
                .DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, com.sun.jna.ptr.IntByReference(DWMWCP_ROUND), 4)
        }
    }
}
