package com.titanvps.desktop

import com.sun.jna.Library
import com.sun.jna.Native
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

    /** Starts this app again "as administrator" (Windows asks). True if it was started. */
    fun relaunchElevated(): Boolean {
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return false
        if (!exe.endsWith(".exe", ignoreCase = true) || exe.endsWith("java.exe", ignoreCase = true)) return false
        return runCatching {
            val p = ProcessBuilder(
                "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "Start-Process -FilePath '${exe.replace("'", "''")}' -Verb RunAs",
            ).start()
            // Non-zero when the user declined the UAC prompt.
            p.waitFor() == 0
        }.getOrDefault(false)
    }
}

object TunConfig {
    const val INTERFACE = "Titan VPS"

    /** Exe names that must never go into the tunnel (loops otherwise). */
    private val ALWAYS_DIRECT = listOf("xray.exe", "sing-box.exe", "naive.exe", "mieru.exe", "samizdat.exe", "Titan VPS.exe")

    fun build(socksPort: Int, excluded: Collection<String>, logDir: String): String {
        val direct = (ALWAYS_DIRECT + excluded).distinctBy { it.lowercase() }
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
                    .put("final", "proxy")
                    .put(
                        "rules", JSONArray()
                            .put(JSONObject().put("action", "sniff"))
                            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                            .put(JSONObject().put("process_name", JSONArray(direct)).put("outbound", "direct"))
                            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct")),
                    ),
            )
            .toString(2)
    }
}

/** The sing-box process (TUN). */
class SingBoxProcess {
    private var process: Process? = null
    private val pidFile = File(AppPaths.dataDir, "tun.pid")

    init {
        // A tunnel left over from a crash would keep Windows' traffic in a dead adapter.
        runCatching { pidFile.readText().trim().toLong() }.getOrNull()?.let { pid ->
            ProcessHandle.of(pid).ifPresent { if (it.info().command().orElse("").endsWith("sing-box.exe")) it.destroyForcibly() }
        }
        pidFile.delete()
    }

    val exe: File get() = File(AppPaths.coreDir, "sing-box.exe")

    fun start(config: String) {
        stop()
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
        "xray.exe", "sing-box.exe", "naive.exe", "mieru.exe", "samizdat.exe", "titan vps.exe", "java.exe", "javaw.exe",
    )

    fun list(): List<App> = ProcessHandle.allProcesses().toList()
        .mapNotNull { p -> p.info().command().orElse(null) }
        .filter { it.endsWith(".exe", ignoreCase = true) && !it.contains("\\Windows\\", ignoreCase = true) }
        .map { App(File(it).name, it) }
        .filter { it.exe.lowercase() !in SYSTEM }
        .distinctBy { it.exe.lowercase() }
        .sortedBy { it.exe.lowercase() }
}
