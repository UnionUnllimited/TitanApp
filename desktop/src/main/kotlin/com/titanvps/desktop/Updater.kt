package com.titanvps.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * App updates from GitHub Releases (tag windows-<build>, asset titan-vps.msi), downloaded
 * only through the VPN (the local HTTP inbound), like on Android.
 */
object Updater {
    data class Release(val build: Int, val name: String, val msiUrl: String)

    private const val RELEASES = "https://api.github.com/repos/UnionUnllimited/TitanApp/releases?per_page=30"
    private const val ASSET = "titan-vps.msi"

    /** "1.0.<build>" from jpackage; 0 when run from the IDE. */
    val currentBuild: Int
        get() = System.getProperty("jpackage.app-version")?.substringAfterLast('.')?.toIntOrNull() ?: 0

    private fun client(proxyPort: Int, readSec: Long = 20) = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .build()

    /** Newest Windows release, or null if this build is the latest. */
    fun findNewer(proxyPort: Int): Release? {
        val body = client(proxyPort).newCall(
            Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build()
        ).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body.string()
        }
        val arr = JSONArray(body)
        val newest = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && it.optString("tag_name").startsWith("windows-") }
            .mapNotNull { rel ->
                val build = rel.optString("tag_name").removePrefix("windows-").toIntOrNull() ?: return@mapNotNull null
                val assets = rel.optJSONArray("assets") ?: return@mapNotNull null
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == ASSET }?.optString("browser_download_url")
                    ?: return@mapNotNull null
                Release(build, rel.optString("name"), url)
            }
            .maxByOrNull { it.build } ?: return null
        return newest.takeIf { it.build > currentBuild }
    }

    fun download(release: Release, proxyPort: Int, onProgress: (Float) -> Unit): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "TitanVPS-update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "titan-vps-${release.build}.msi")
        client(proxyPort, readSec = 60).newCall(Request.Builder().url(release.msiUrl).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val total = r.body.contentLength()
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        return file
    }

    /**
     * Installs [msi] after this app exits and starts the new version. The caller must
     * quit right after (the running app's files are locked).
     */
    fun installAndRestart(msi: File) {
        val exe = ProcessHandle.current().info().command().orElse(null)
            ?.takeIf { it.endsWith(".exe", ignoreCase = true) && !it.endsWith("java.exe", ignoreCase = true) }
        val pid = ProcessHandle.current().pid()
        val coreDir = AppPaths.coreDir.absolutePath
        val script = File(msi.parentFile, "update.cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("chcp 65001 >nul") // paths may contain Cyrillic (user name)
                // Wait for the app to quit, then make sure none of our xray.exe keep files
                // locked: a locked file makes Windows Installer hang on "configuring".
                appendLine(":wait")
                appendLine("tasklist /FI \"PID eq $pid\" | find \"$pid\" >nul && (timeout /t 1 /nobreak >nul & goto wait)")
                appendLine("powershell -NoProfile -Command \"Get-Process xray,sing-box -ErrorAction SilentlyContinue | Where-Object { \$_.Path -like '$coreDir*' } | Stop-Process -Force\"")
                appendLine("timeout /t 1 /nobreak >nul")
                appendLine("msiexec /i \"${msi.absolutePath}\" /passive /norestart")
                if (exe != null) appendLine("start \"\" \"$exe\"")
            }.replace("\n", "\r\n"),
            Charsets.UTF_8,
        )
        ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "/min", script.absolutePath).start()
    }
}
