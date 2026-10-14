package com.titanvps.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * App updates from GitHub Releases (tag windows-<build>, asset titan-vps-setup.exe — our
 * Inno Setup installer, run silently), downloaded only through the VPN (the local HTTP
 * inbound), like on Android.
 */
object Updater {
    data class Release(val build: Int, val name: String, val url: String)

    private const val RELEASES = "https://api.github.com/repos/UnionUnllimited/TitanApp/releases?per_page=30"
    private const val ASSET = "titan-vps-setup.exe"

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
            Request.Builder().url(RELEASES).header("User-Agent", Config.TITAN_UA).header("Accept", "application/vnd.github+json").build()
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
        val file = File(dir, "titan-vps-setup-${release.build}.exe")
        client(proxyPort, readSec = 60).newCall(Request.Builder().url(release.url).header("User-Agent", Config.TITAN_UA).build()).execute().use { r ->
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
     * Installs [setup] silently after this app exits; the installer starts the new
     * version. The caller must quit right after (the running app's files are locked).
     *
     * A PowerShell script does it with no console window (Java starts it with
     * CREATE_NO_WINDOW), showing its own "Обновляем Titan VPS…" window instead until the
     * new version is up.
     */
    fun installAndRestart(setup: File) {
        val pid = ProcessHandle.current().pid()
        val coreDir = AppPaths.coreDir.absolutePath
        // Where the installer puts the app (DefaultDirName in titan.iss); else this exe.
        val installed = File(System.getenv("LOCALAPPDATA") ?: "", "Programs\\Titan VPS\\Titan VPS.exe")
        val exe = installed.takeIf { it.isFile }?.absolutePath
            ?: ProcessHandle.current().info().command().orElse(null)
        fun q(path: String) = "'" + path.replace("'", "''") + "'" // PowerShell single quotes
        // Our logo for the window (the app's resources are gone once it quits).
        val logo = File(setup.parentFile, "titan-logo.png")
        runCatching { Updater::class.java.getResourceAsStream("/logo.png")?.use { input -> logo.outputStream().use { input.copyTo(it) } } }
        val script = File(setup.parentFile, "update.ps1")
        script.writeText(
            "\uFEFF" + // UTF-8 with BOM: Windows PowerShell reads Cyrillic paths right
                """
                ${'$'}ErrorActionPreference = 'SilentlyContinue'
                Add-Type -AssemblyName System.Windows.Forms, System.Drawing
                [System.Windows.Forms.Application]::EnableVisualStyles()
                ${'$'}form = New-Object System.Windows.Forms.Form
                ${'$'}form.Text = 'Titan VPS'
                ${'$'}form.FormBorderStyle = 'FixedDialog'
                ${'$'}form.ControlBox = ${'$'}false
                ${'$'}form.StartPosition = 'CenterScreen'
                ${'$'}form.TopMost = ${'$'}true
                ${'$'}form.ClientSize = New-Object System.Drawing.Size(420, 140)
                ${'$'}form.BackColor = [System.Drawing.Color]::FromArgb(14, 19, 29)
                # Titan's icon on the window and in the taskbar, its logo inside.
                ${'$'}logo = [System.Drawing.Image]::FromFile(${q(logo.absolutePath)})
                ${'$'}appIcon = ${if (exe != null) "[System.Drawing.Icon]::ExtractAssociatedIcon(${q(exe)})" else "${'$'}null"}
                if (-not ${'$'}appIcon -and ${'$'}logo) { ${'$'}appIcon = [System.Drawing.Icon]::FromHandle((New-Object System.Drawing.Bitmap(${'$'}logo, 64, 64)).GetHicon()) }
                if (${'$'}appIcon) { ${'$'}form.Icon = ${'$'}appIcon }
                ${'$'}pic = New-Object System.Windows.Forms.PictureBox
                ${'$'}pic.Image = ${'$'}logo
                ${'$'}pic.SizeMode = 'Zoom'
                ${'$'}pic.Location = New-Object System.Drawing.Point(24, 22)
                ${'$'}pic.Size = New-Object System.Drawing.Size(60, 60)
                ${'$'}title = New-Object System.Windows.Forms.Label
                ${'$'}title.Text = 'Обновляем Titan VPS…'
                ${'$'}title.Font = New-Object System.Drawing.Font('Segoe UI', 15, [System.Drawing.FontStyle]::Bold)
                ${'$'}title.ForeColor = [System.Drawing.Color]::White
                ${'$'}title.Location = New-Object System.Drawing.Point(96, 20)
                ${'$'}title.Size = New-Object System.Drawing.Size(310, 34)
                ${'$'}sub = New-Object System.Windows.Forms.Label
                ${'$'}sub.Text = 'Подождите, приложение откроется само'
                ${'$'}sub.Font = New-Object System.Drawing.Font('Segoe UI', 10)
                ${'$'}sub.ForeColor = [System.Drawing.Color]::FromArgb(160, 170, 190)
                ${'$'}sub.Location = New-Object System.Drawing.Point(96, 58)
                ${'$'}sub.Size = New-Object System.Drawing.Size(310, 24)
                ${'$'}bar = New-Object System.Windows.Forms.ProgressBar
                ${'$'}bar.Style = 'Marquee'
                ${'$'}bar.MarqueeAnimationSpeed = 30
                ${'$'}bar.Location = New-Object System.Drawing.Point(24, 100)
                ${'$'}bar.Size = New-Object System.Drawing.Size(372, 12)
                ${'$'}form.Controls.AddRange(@(${'$'}pic, ${'$'}title, ${'$'}sub, ${'$'}bar))
                ${'$'}form.Show()
                function Pump([double]${'$'}sec) {
                    ${'$'}end = (Get-Date).AddSeconds(${'$'}sec)
                    do { [System.Windows.Forms.Application]::DoEvents(); Start-Sleep -Milliseconds 50 } while ((Get-Date) -lt ${'$'}end)
                }
                # Wait for the app to quit, then make sure none of our xray.exe keep files
                # locked: a locked file makes the installer hang.
                while (Get-Process -Id $pid -ErrorAction SilentlyContinue) { Pump 0.5 }
                Get-Process xray, sing-box | Where-Object { ${'$'}_.Path -like ${q("$coreDir*")} } | Stop-Process -Force
                Pump 1
                ${'$'}setup = Start-Process -FilePath ${q(setup.absolutePath)} -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CLOSEAPPLICATIONS' -PassThru
                while (-not ${'$'}setup.HasExited) { Pump 0.5 }
                Pump 3
                """.trimIndent() + "\n" +
                // The installer should start the new version itself, but doesn't always.
                (if (exe != null) "if (-not (Get-Process 'Titan VPS')) { Start-Process -FilePath ${q(exe)} }\n" else "") +
                """
                # The window stays until the new version shows its own.
                ${'$'}until = (Get-Date).AddSeconds(45)
                while ((Get-Date) -lt ${'$'}until) {
                    if (Get-Process 'Titan VPS' | Where-Object { ${'$'}_.MainWindowHandle -ne 0 }) { break }
                    Pump 0.5
                }
                ${'$'}form.Close()
                """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}
