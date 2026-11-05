package com.titanvps.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TUN mode: sing-box makes a virtual network adapter and routes the whole PC into it
 * (games, Discord, everything), handing the traffic to our Xray core over local SOCKS.
 * Programs from the exclusion list, and our own cores, go straight to the internet.
 * Same scheme as v2rayN; needs administrator rights.
 */
object Admin {
    @Suppress("FunctionName")
    private interface Shell : Library {
        fun IsUserAnAdmin(): Boolean
    }

    val isAdmin: Boolean
        get() = !isWindows || runCatching { Native.load("shell32", Shell::class.java).IsUserAnAdmin() }.getOrDefault(false)

    @Suppress("FunctionName")
    private interface Token : Library {
        fun GetTokenInformation(token: WinNT.HANDLE, infoClass: Int, info: IntByReference, length: Int, returned: IntByReference): Boolean
    }

    /**
     * TOKEN_ELEVATION_TYPE of this process: 1 no split token (a standard account, or UAC off),
     * 2 elevated, 3 an administrator under UAC; null if Windows didn't say.
     */
    private fun elevationType(): Int? = runCatching {
        val ref = WinNT.HANDLEByReference()
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, ref)) return null
        try {
            val type = IntByReference()
            val ok = Native.load("advapi32", Token::class.java)
                .GetTokenInformation(ref.value, WinNT.TOKEN_INFORMATION_CLASS.TokenElevationType, type, 4, IntByReference())
            if (ok) type.value else null
        } finally {
            Kernel32.INSTANCE.CloseHandle(ref.value)
        }
    }.getOrNull()

    /**
     * «Перезапустить» can give this account administrator rights: it is an administrator
     * under UAC (Windows only asks «Да»), or already has them. On a standard account Windows
     * asks for another account's password, and the app would open in that account's profile:
     * without the key, the settings or this user's system proxy.
     */
    val canElevate: Boolean by lazy { !isWindows || canElevate(isAdmin, elevationType()) }

    /** [canElevate] from the facts: not known ([type] null) counts as yes, as before. */
    fun canElevate(admin: Boolean, type: Int?): Boolean = admin || type != TOKEN_ELEVATION_TYPE_DEFAULT

    private const val TOKEN_ELEVATION_TYPE_DEFAULT = 1

    private var tfoEnabled = false

    /**
     * Windows keeps outgoing TCP Fast Open off by default; turn it on (needs admin) the
     * first time a TFO host connects. Without admin it's left as is.
     */
    fun enableTcpFastOpen() {
        if (tfoEnabled || !isWindows || !isAdmin) return
        tfoEnabled = runCatching {
            ProcessBuilder("netsh", "int", "tcp", "set", "global", "fastopen=enabled")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        }.getOrDefault(false)
    }

    /** Starts this app again "as administrator" (Windows asks). True if it was started. */
    fun relaunchElevated(): Boolean {
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return false
        if (!exe.endsWith(".exe", ignoreCase = true) || exe.endsWith("java.exe", ignoreCase = true)) return false
        return runCatching {
            val p = ProcessBuilder(
                "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                // --elevated: the new copy waits for this one to quit (one copy at a time).
                "Start-Process -FilePath ${psQuoted(exe)} -ArgumentList '--elevated' -Verb RunAs",
            ).start()
            // Non-zero when the user declined the UAC prompt.
            p.waitFor() == 0
        }.getOrDefault(false)
    }
}

object TunConfig {
    const val INTERFACE = "Titan VPS"

    /** Exe names that must never go into the tunnel (loops otherwise). */
    private val ALWAYS_DIRECT = listOf("xray.exe", "sing-box.exe", "naive.exe", "mieru.exe", "Titan VPS.exe")

    /**
     * [apps] bypass the VPN, or with [only] are the only programs that go through it (with
     * nothing picked then, nothing does).
     */
    fun build(socksPort: Int, apps: Collection<String>, only: Boolean, logDir: String): String {
        val direct = (ALWAYS_DIRECT + if (only) emptyList() else apps).distinctBy { it.lowercase() }
        val viaVpn = apps.filterNot { a -> ALWAYS_DIRECT.any { it.equals(a, true) } }.distinctBy { it.lowercase() }
        return JSONObject()
            .put("log", JSONObject().put("level", "warn").put("output", "$logDir/sing-box.log").put("timestamp", true))
            .put(
                "dns", JSONObject()
                    .put(
                        "servers", JSONArray()
                            // Queries from apps in the tunnel: resolved over TCP through the VPN.
                            .put(JSONObject().put("type", "tcp").put("tag", "remote").put("server", "1.1.1.1").put("detour", "proxy"))
                            .put(JSONObject().put("type", "local").put("tag", "local")),
                    )
                    .put(
                        "rules", JSONArray()
                            .put(JSONObject().put("process_name", JSONArray(direct)).put("server", "local")),
                    )
                    // Also with "only": Windows resolves for every program in its DNS service
                    // (svchost), so a per-program DNS rule wouldn't match; through the VPN the
                    // answers for blocked sites aren't spoofed.
                    .put("final", "remote")
                    .put("strategy", "ipv4_only"),
            )
            .put(
                "inbounds", JSONArray().put(
                    JSONObject()
                        .put("type", "tun").put("tag", "tun-in")
                        .put("interface_name", INTERFACE)
                        .put("address", JSONArray().put("172.19.0.1/30"))
                        .put("mtu", 9000)
                        .put("auto_route", true)
                        .put("strict_route", true)
                        .put("stack", "mixed"),
                ),
            )
            .put(
                "outbounds", JSONArray()
                    .put(JSONObject().put("type", "socks").put("tag", "proxy").put("server", "127.0.0.1").put("server_port", socksPort).put("version", "5"))
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .put(
                "route", JSONObject()
                    .put("auto_detect_interface", true)
                    .put("default_domain_resolver", "local")
                    .put("final", if (only) "direct" else "proxy")
                    .put(
                        "rules", JSONArray()
                            .put(JSONObject().put("action", "sniff"))
                            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                            .put(JSONObject().put("process_name", JSONArray(direct)).put("outbound", "direct"))
                            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
                            .apply { if (only && viaVpn.isNotEmpty()) put(JSONObject().put("process_name", JSONArray(viaVpn)).put("outbound", "proxy")) },
                    ),
            )
            .toString(2)
    }
}

/** The sing-box process (TUN). */
class SingBoxProcess {
    @Volatile private var process: Process? = null
    private val pidFile = File(AppPaths.dataDir, "tun.pid")
    /** The config of the last start (kept after an exit, for [restart]). */
    private var config: String? = null

    init {
        // A tunnel left over from a crash would keep Windows' traffic in a dead adapter.
        runCatching { pidFile.readText().trim().toLong() }.getOrNull()?.let { pid ->
            ProcessHandle.of(pid).ifPresent { if (it.info().command().orElse("").endsWith("sing-box.exe")) it.destroyForcibly() }
        }
        pidFile.delete()
    }

    val exe: File get() = File(AppPaths.coreDir, "sing-box.exe")

    val isRunning: Boolean get() = process?.isAlive == true

    /** Completes when sing-box exits (whoever ends it); null when it isn't running. */
    fun exits(): java.util.concurrent.CompletableFuture<Process>? = process?.onExit()

    /**
     * Runs the tunnel with [config]. Already running with the same config (a server switch: the
     * new core took the old SOCKS port), it is kept: stopping sing-box takes Windows' adapter,
     * its routes and the DNS filters with it, and until a new one has them back, apps and DNS
     * would go around the VPN.
     */
    @Synchronized
    fun start(config: String) {
        if (isRunning && config == this.config) return
        stop()
        run(config)
    }

    /** The tunnel again after sing-box exited, with the same config. */
    @Synchronized
    fun restart() {
        val c = config ?: throw IllegalStateException("TUN ещё не запускался")
        stop()
        run(c)
    }

    private fun run(config: String) {
        this.config = config
        if (!exe.exists()) throw IllegalStateException("Не найден ${exe.absolutePath}")
        val configFile = File(AppPaths.dataDir, "tun.json").apply { writeText(config) }
        val out = File(AppPaths.logDir, "sing-box-stdout.log")
        val p = ProcessBuilder(exe.absolutePath, "run", "-c", configFile.absolutePath, "--disable-color")
            .directory(AppPaths.dataDir)
            .redirectErrorStream(true)
            .redirectOutput(out)
            .start()
        process = p
        pidFile.writeText(p.pid().toString())
        // The adapter and routes take a moment.
        Thread.sleep(1500)
        if (!p.isAlive) {
            val tail = runCatching { out.readLines().takeLast(5).joinToString("\n") }.getOrDefault("")
            process = null
            throw IllegalStateException("TUN не запустился:\n$tail")
        }
    }

    @Synchronized
    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        pidFile.delete()
    }
}

/** Programs for the exclusion list: what is running now (one entry per exe name). */
object RunningApps {
    data class App(val exe: String, val path: String)

    private val SYSTEM = setOf(
        "svchost.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe", "lsass.exe", "smss.exe",
        "dwm.exe", "fontdrvhost.exe", "conhost.exe", "runtimebroker.exe", "sihost.exe", "taskhostw.exe",
        "ctfmon.exe", "searchindexer.exe", "system", "registry", "dllhost.exe", "wmiprvse.exe",
        "xray.exe", "sing-box.exe", "naive.exe", "mieru.exe", "titan vps.exe", "java.exe", "javaw.exe",
    )

    fun list(): List<App> = ProcessHandle.allProcesses().toList()
        .mapNotNull { p -> p.info().command().orElse(null) }
        .filter { it.endsWith(".exe", ignoreCase = true) && !it.contains("\\Windows\\", ignoreCase = true) }
        .map { App(File(it).name, it) }
        .filter { it.exe.lowercase() !in SYSTEM }
        .distinctBy { it.exe.lowercase() }
        .sortedBy { it.exe.lowercase() }
}
