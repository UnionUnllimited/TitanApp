package com.titanvps.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
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
    const val PROXY_TYPE_DIRECT = 1
    const val PROXY_TYPE_PROXY = 2
    const val PROXY_TYPE_AUTO_PROXY_URL = 4
    const val PROXY_TYPE_AUTO_DETECT = 8

    private interface WinInet : Library {
        fun InternetSetOptionW(hInternet: Pointer?, option: Int, buffer: Pointer?, length: Int): Boolean
    }

    fun enable(httpPort: Int) {
        if (!isWindows) return
        if (!backup.exists()) {
            backup.writeText(JSONObject()
                .put("enable", readInt("ProxyEnable"))
                .put("server", readString("ProxyServer"))
                .put("override", readString("ProxyOverride"))
                .put("pac", readString("AutoConfigURL"))
                .apply { connectionFlags()?.let { put("flags", it) } }
                .toString())
        }
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", 1)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer", "127.0.0.1:$httpPort")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride", BYPASS)
        // A setup script (PAC) or «Определять параметры автоматически» (WPAD) comes before a
        // manual proxy in browsers and Windows: their DIRECT answers would go around the VPN
        // while the app says «Подключено». Only our proxy while on (as v2rayN and Clash); both
        // come back with restore().
        deleteValue("AutoConfigURL")
        setPerConnection(PROXY_TYPE_DIRECT or PROXY_TYPE_PROXY, "127.0.0.1:$httpPort", BYPASS, pac = null)
        notifyChanged()
    }

    /** Puts back what was there before we connected. */
    fun restore() {
        if (!isWindows || !backup.exists()) return
        runCatching {
            val o = JSONObject(backup.readText())
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
            if (o.has("flags")) setPerConnection(o.getInt("flags"), server, override, pac.ifEmpty { null })
        }.onFailure {
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", 0)
        }
        backup.delete()
        notifyChanged()
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
    data class PerConnLayout(val optionSize: Int, val valueOffset: Int, val listSize: Int, val countOffset: Int, val optionsOffset: Int) {
        companion object {
            fun of(pointerSize: Int) = PerConnLayout(
                optionSize = if (pointerSize == 8) 16 else 12,
                valueOffset = if (pointerSize == 8) 8 else 4,
                listSize = 3 * pointerSize + 8,
                countOffset = 2 * pointerSize,
                optionsOffset = 2 * pointerSize + 8,
            )
        }
    }

    /**
     * The LAN connection's settings through WinINet itself, which keeps its own copy
     * (Connections\DefaultConnectionSettings) and the values above in step: [flags]
     * (PROXY_TYPE_*), the proxy and its exceptions, and the setup script's address unless
     * [pac] is null. False if Windows refused.
     */
    private fun setPerConnection(flags: Int, server: String, bypass: String, pac: String?): Boolean = runCatching {
        val l = PerConnLayout.of(Native.POINTER_SIZE)
        val strings = ArrayList<com.sun.jna.Memory>()
        fun wide(text: String) = com.sun.jna.Memory((text.length + 1L) * Native.WCHAR_SIZE).apply { setWideString(0, text) }.also { strings += it }
        val values: List<Pair<Int, Any>> = listOfNotNull(
            PER_CONN_FLAGS to flags,
            PER_CONN_PROXY_SERVER to server,
            PER_CONN_PROXY_BYPASS to bypass,
            pac?.let { PER_CONN_AUTOCONFIG_URL to it },
        )
        val options = com.sun.jna.Memory(l.optionSize.toLong() * values.size).apply { clear() }
        values.forEachIndexed { i, (option, value) ->
            val at = i.toLong() * l.optionSize
            options.setInt(at, option)
            when (value) {
                is Int -> options.setInt(at + l.valueOffset, value)
                else -> options.setPointer(at + l.valueOffset, wide(value as String))
            }
        }
        val list = com.sun.jna.Memory(l.listSize.toLong()).apply { clear() }
        list.setInt(0, l.listSize)
        // pszConnection stays NULL: the LAN connection.
        list.setInt(l.countOffset.toLong(), values.size)
        list.setPointer(l.optionsOffset.toLong(), options)
        val ok = Native.load("wininet", WinInet::class.java).InternetSetOptionW(null, INTERNET_OPTION_PER_CONNECTION_OPTION, list, l.listSize)
        // Native memory freed only once WinINet has copied it.
        java.lang.ref.Reference.reachabilityFence(options)
        java.lang.ref.Reference.reachabilityFence(strings)
        ok
    }.getOrDefault(false)

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
