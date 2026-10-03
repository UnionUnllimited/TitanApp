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
    private val backup = File(AppPaths.dataDir, "proxy-backup.json")
    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;" +
        "172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

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
                .toString())
        }
        Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", 1)
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer", "127.0.0.1:$httpPort")
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride", BYPASS)
        notifyChanged()
    }

    /** Puts back what was there before we connected. */
    fun restore() {
        if (!isWindows || !backup.exists()) return
        runCatching {
            val o = JSONObject(backup.readText())
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", o.optInt("enable", 0))
            o.optString("server").let { if (it.isEmpty()) deleteValue("ProxyServer") else
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyServer", it) }
            o.optString("override").let { if (it.isEmpty()) deleteValue("ProxyOverride") else
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyOverride", it) }
        }.onFailure {
            Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, KEY, "ProxyEnable", 0)
        }
        backup.delete()
        notifyChanged()
    }

    private fun readInt(name: String) = runCatching { Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, KEY, name) }.getOrDefault(0)
    private fun readString(name: String) = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, name) }.getOrDefault("")
    private fun deleteValue(name: String) = runCatching { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, name) }

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
    private var process: Process? = null
    private val pidFile = File(AppPaths.dataDir, "$name.pid")

    init {
        // A core left over from a crash would keep the ports busy.
        runCatching { pidFile.readText().trim().toLong() }.getOrNull()?.let { pid ->
            ProcessHandle.of(pid).ifPresent { if (it.info().command().orElse("").endsWith("xray.exe")) it.destroyForcibly() }
        }
        pidFile.delete()
    }

    val isRunning: Boolean get() = process?.isAlive == true

    /** Starts the core; throws with Xray's own message if it exits right away. */
    fun start(config: String, assetDir: File = AppPaths.coreDir) {
        stop()
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

    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        pidFile.delete()
    }
}
