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

    private const val RELEASES = "https://api.github.com/repos/UnionUnllimited/TitanApp/releases"
    private const val ASSET = "titan-vps-setup.exe"
    /**
     * The list comes newest commit first, every app's builds together (iOS, Android's tests,
     * win-test-*): a day of them pushed the last windows-<n> off one page of 30, and the
     * update was missed. GitHub gives at most 100 a page.
     */
    const val PER_PAGE = 100
    const val MAX_PAGES = 3
    private val TAG = Regex("windows-(\\d{1,9})")

    /** "1.0.<build>" from jpackage; 0 when run from the IDE. */
    val currentBuild: Int
        get() = System.getProperty("jpackage.app-version")?.substringAfterLast('.')?.toIntOrNull() ?: 0

    private fun client(proxyPort: Int, readSec: Long = 20) = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .build()

    /**
     * Newest Windows release, or null if this build is the latest. Throws an [java.io.IOException]
     * when GitHub can't be reached through the VPN, any other exception when it answered
     * with an error (an HTTP status, a page that isn't the list).
     */
    fun findNewer(proxyPort: Int): Release? {
        val client = client(proxyPort)
        val newest = newestWindows { page ->
            client.newCall(
                Request.Builder().url("$RELEASES?per_page=$PER_PAGE&page=$page")
                    .header("User-Agent", Config.TITAN_UA).header("Accept", "application/vnd.github+json").build()
            ).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body.string()
            }
        } ?: return null
        return newest.takeIf { it.build > currentBuild }
    }

    /**
     * The newest windows-<build> release with our installer, reading [page] (1, 2, …: the
     * list's pages of [PER_PAGE], newest first) until one has any: its highest build. At most
     * [MAX_PAGES], and not past the last page. Test builds (win-test-<n>) never count.
     */
    internal fun newestWindows(page: (Int) -> String): Release? {
        for (n in 1..MAX_PAGES) {
            val arr = JSONArray(page(n))
            windowsReleases(arr).maxByOrNull { it.build }?.let { return it }
            if (arr.length() < PER_PAGE) break
        }
        return null
    }

    /** The windows-<build> releases of one page that carry [ASSET] (no drafts). */
    internal fun windowsReleases(arr: JSONArray): List<Release> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .mapNotNull { rel ->
                val build = TAG.matchEntire(rel.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val assets = rel.optJSONArray("assets") ?: return@mapNotNull null
                val url = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == ASSET }?.optString("browser_download_url")
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                Release(build, rel.optString("name"), url)
            }

    fun download(release: Release, proxyPort: Int, onProgress: (Float) -> Unit): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "TitanVPS-update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "titan-vps-setup-${release.build}.exe")
        client(proxyPort, readSec = 60).newCall(Request.Builder().url(release.url).header("User-Agent", Config.TITAN_UA).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val total = r.body.contentLength()
            r.body.byteStream().use { input -> save(input, { file.outputStream() }, total, onProgress) }
        }
        return file
    }

    /**
     * The disk failed (full, the file locked or not created), not the network: no other server
     * would help, so the update doesn't move the user to АВТО for it (only an IOException does).
     */
    class DiskError(cause: java.io.IOException) : IllegalStateException("disk: ${cause.message}", cause)

    private inline fun <T> disk(block: () -> T): T =
        try { block() } catch (e: java.io.IOException) { throw DiskError(e) }

    /**
     * [input] (the network) into the file [open] gives: a failed read stays an IOException, a
     * failed file a [DiskError]. [onProgress] with the share of [total] done (none when unknown).
     */
    internal fun save(input: java.io.InputStream, open: () -> java.io.OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val out = disk(open)
        try {
            val buf = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf) // the network: IOException, another server may help
                if (n < 0) break
                disk { out.write(buf, 0, n) } // the disk (no space): DiskError
                done += n
                if (total > 0) onProgress(done.toFloat() / total)
            }
            disk { out.close() } // what is still buffered goes to the disk here
        } finally {
            runCatching { out.close() }
        }
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
        // Where the installer puts the app (DefaultDirName in titan.iss); else this exe.
        val installed = File(System.getenv("LOCALAPPDATA") ?: "", "Programs\\Titan VPS\\Titan VPS.exe")
        val exe = installed.takeIf { it.isFile }?.absolutePath
            ?: ProcessHandle.current().info().command().orElse(null)
        // Our logo for the window (the app's resources are gone once it quits).
        val logo = File(setup.parentFile, "titan-logo.png")
        runCatching { Updater::class.java.getResourceAsStream("/logo.png")?.use { input -> logo.outputStream().use { input.copyTo(it) } } }
        val script = File(setup.parentFile, "update.ps1")
        script.writeText(
            "\uFEFF" + // UTF-8 with BOM: Windows PowerShell reads Cyrillic paths right
                updateScript(ProcessHandle.current().pid(), AppPaths.coreDir.absolutePath, exe, logo.absolutePath, setup.absolutePath),
            Charsets.UTF_8,
        )
        ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /** How long the script waits for the app to quit, then for the installer, before it gives up. */
    const val WAIT_MINUTES = 15

    /**
     * The update script: waits for the app (process [pid]) to quit, stops our cores in
     * [coreDir], runs [setup] silently, then makes sure [exe] (the installed app) is running.
     */
    internal fun updateScript(pid: Long, coreDir: String, exe: String?, logo: String, setup: String): String {
        fun q(path: String) = "'" + path.replace("'", "''") + "'" // PowerShell single quotes
        return """
                ${'$'}ErrorActionPreference = 'SilentlyContinue'
                Add-Type -AssemblyName System.Windows.Forms, System.Drawing
                # Sharp at any display scale (else Windows stretches the window and blurs it), and
                # Windows 11's rounded corners.
                Add-Type @'
                using System;
                using System.Runtime.InteropServices;
                public static class TitanWin {
                    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
                    [DllImport("dwmapi.dll")] public static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
                }
                '@
                [TitanWin]::SetProcessDPIAware() | Out-Null
                [System.Windows.Forms.Application]::EnableVisualStyles()
                ${'$'}g = [System.Drawing.Graphics]::FromHwnd([IntPtr]::Zero); ${'$'}k = ${'$'}g.DpiX / 96; ${'$'}g.Dispose()
                function S([double]${'$'}v) { [int][Math]::Round(${'$'}v * ${'$'}k) }
                ${'$'}bg = [System.Drawing.Color]::FromArgb(14, 19, 29)
                # Our own window: no system frame, the app's colours.
                ${'$'}form = New-Object System.Windows.Forms.Form
                ${'$'}form.Text = 'Titan VPS'
                ${'$'}form.FormBorderStyle = 'None'
                ${'$'}form.StartPosition = 'CenterScreen'
                ${'$'}form.TopMost = ${'$'}true
                ${'$'}form.ShowInTaskbar = ${'$'}true
                ${'$'}form.BackColor = ${'$'}bg
                ${'$'}form.ClientSize = New-Object System.Drawing.Size((S 440), (S 148))
                ${'$'}form.Add_Load({ ${'$'}round = 2; [TitanWin]::DwmSetWindowAttribute(${'$'}form.Handle, 33, [ref]${'$'}round, 4) | Out-Null })
                ${'$'}edge = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(40, 48, 64))
                ${'$'}form.Add_Paint({ ${'$'}_.Graphics.DrawRectangle(${'$'}edge, 0, 0, ${'$'}form.ClientSize.Width - 1, ${'$'}form.ClientSize.Height - 1) })
                # Titan's icon in the taskbar; the logo scaled smoothly to its exact size.
                ${'$'}src = [System.Drawing.Image]::FromFile(${q(logo)})
                ${'$'}appIcon = ${if (exe != null) "[System.Drawing.Icon]::ExtractAssociatedIcon(${q(exe)})" else "${'$'}null"}
                if (-not ${'$'}appIcon -and ${'$'}src) { ${'$'}appIcon = [System.Drawing.Icon]::FromHandle((New-Object System.Drawing.Bitmap(${'$'}src, 64, 64)).GetHicon()) }
                if (${'$'}appIcon) { ${'$'}form.Icon = ${'$'}appIcon }
                ${'$'}size = S 64
                ${'$'}logo = New-Object System.Drawing.Bitmap(${'$'}size, ${'$'}size)
                ${'$'}lg = [System.Drawing.Graphics]::FromImage(${'$'}logo)
                ${'$'}lg.InterpolationMode = 'HighQualityBicubic'
                ${'$'}lg.SmoothingMode = 'HighQuality'
                ${'$'}lg.PixelOffsetMode = 'HighQuality'
                if (${'$'}src) { ${'$'}lg.DrawImage(${'$'}src, 0, 0, ${'$'}size, ${'$'}size) }
                ${'$'}lg.Dispose()
                ${'$'}pic = New-Object System.Windows.Forms.PictureBox
                ${'$'}pic.Image = ${'$'}logo
                ${'$'}pic.BackColor = ${'$'}bg
                ${'$'}pic.Location = New-Object System.Drawing.Point((S 28), (S 30))
                ${'$'}pic.Size = New-Object System.Drawing.Size(${'$'}size, ${'$'}size)
                ${'$'}title = New-Object System.Windows.Forms.Label
                ${'$'}title.Text = 'Обновляем Titan VPS'
                ${'$'}title.Font = New-Object System.Drawing.Font('Segoe UI Semibold', 15)
                ${'$'}title.ForeColor = [System.Drawing.Color]::White
                ${'$'}title.BackColor = ${'$'}bg
                ${'$'}title.Location = New-Object System.Drawing.Point((S 108), (S 32))
                ${'$'}title.Size = New-Object System.Drawing.Size((S 310), (S 34))
                ${'$'}sub = New-Object System.Windows.Forms.Label
                ${'$'}sub.Text = 'Подождите, приложение откроется само'
                ${'$'}sub.Font = New-Object System.Drawing.Font('Segoe UI', 10)
                ${'$'}sub.ForeColor = [System.Drawing.Color]::FromArgb(139, 149, 170)
                ${'$'}sub.BackColor = ${'$'}bg
                ${'$'}sub.Location = New-Object System.Drawing.Point((S 109), (S 68))
                ${'$'}sub.Size = New-Object System.Drawing.Size((S 310), (S 24))
                # Progress: a strip in the logo's colours running along a dark track.
                ${'$'}track = New-Object System.Windows.Forms.Panel
                ${'$'}track.BackColor = [System.Drawing.Color]::FromArgb(31, 38, 51)
                ${'$'}track.Location = New-Object System.Drawing.Point((S 28), (S 118))
                ${'$'}track.Size = New-Object System.Drawing.Size((S 384), (S 6))
                ${'$'}script:pos = 0.0
                ${'$'}track.Add_Paint({
                    ${'$'}w = ${'$'}track.ClientSize.Width; ${'$'}h = ${'$'}track.ClientSize.Height
                    ${'$'}seg = [int](${'$'}w * 0.35)
                    ${'$'}r = New-Object System.Drawing.Rectangle([int]((${'$'}script:pos - 0.35) * ${'$'}w), 0, ${'$'}seg, ${'$'}h)
                    ${'$'}brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(${'$'}r, [System.Drawing.Color]::FromArgb(34, 211, 238), [System.Drawing.Color]::FromArgb(168, 85, 247), [single]0)
                    ${'$'}_.Graphics.FillRectangle(${'$'}brush, ${'$'}r)
                    ${'$'}brush.Dispose()
                })
                ${'$'}timer = New-Object System.Windows.Forms.Timer
                ${'$'}timer.Interval = 15
                ${'$'}timer.Add_Tick({ ${'$'}script:pos = (${'$'}script:pos + 0.012) % 1.35; ${'$'}track.Invalidate() })
                ${'$'}form.Controls.AddRange(@(${'$'}pic, ${'$'}title, ${'$'}sub, ${'$'}track))
                ${'$'}form.Show()
                ${'$'}timer.Start()
                function Pump([double]${'$'}sec) {
                    ${'$'}end = (Get-Date).AddSeconds(${'$'}sec)
                    do { [System.Windows.Forms.Application]::DoEvents(); Start-Sleep -Milliseconds 15 } while ((Get-Date) -lt ${'$'}end)
                }
                # Never waits for good: past this the window goes and the app is left as it is.
                ${'$'}deadline = (Get-Date).AddMinutes($WAIT_MINUTES)
                # Wait for the app to quit, then make sure none of our xray.exe keep files
                # locked: a locked file makes the installer hang.
                while ((Get-Process -Id $pid -ErrorAction SilentlyContinue) -and (Get-Date) -lt ${'$'}deadline) { Pump 0.5 }
                if (Get-Process -Id $pid -ErrorAction SilentlyContinue) { ${'$'}form.Close(); exit }
                Get-Process xray, sing-box | Where-Object { ${'$'}_.Path -like ${q("$coreDir*")} } | Stop-Process -Force
                Pump 1
                # Null when Windows wouldn't start it (an antivirus took it): nothing was installed,
                # the old version opens again below.
                ${'$'}setup = Start-Process -FilePath ${q(setup)} -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CLOSEAPPLICATIONS' -PassThru
                while (${'$'}setup -and -not ${'$'}setup.HasExited -and (Get-Date) -lt ${'$'}deadline) { Pump 0.5 }
                # Still installing (hung, or a message hidden under this window): the app isn't
                # started over files being replaced.
                if (${'$'}setup -and -not ${'$'}setup.HasExited) { ${'$'}form.Close(); exit }
                Pump 3
                """.trimIndent() + "\n" +
                // The installer should start the new version itself, but doesn't always.
                (if (exe != null) "if (-not (Get-Process 'Titan VPS')) { Start-Process -FilePath ${q(exe)} }\n" else "") +
                """
                # The window stays until the new version starts: its own start-up window (the
                # splash) shows the progress from there, not under this one.
                ${'$'}until = (Get-Date).AddSeconds(45)
                while ((Get-Date) -lt ${'$'}until) {
                    if (Get-Process 'Titan VPS') { Pump 0.7; break }
                    Pump 0.3
                }
                ${'$'}form.Close()
                """.trimIndent() + "\n"
    }
}
