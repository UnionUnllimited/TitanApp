package com.titanvps.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * «Обновление приложения»: which release is offered (every app's builds share one list, newest
 * commit first, read page by page), a disk error told from a network one, the update script's
 * waits, and the one-time reconnect after an update saved in state.json.
 */
class UpdaterTest {

    private fun release(tag: String, asset: String? = "titan-vps-setup.exe", draft: Boolean = false) = JSONObject()
        .put("tag_name", tag)
        .put("name", "Titan VPS $tag")
        .put("draft", draft)
        .put("assets", JSONArray().apply {
            if (asset != null) put(JSONObject().put("name", asset).put("browser_download_url", "https://example.com/$tag/$asset"))
        })

    private fun page(vararg releases: JSONObject) = JSONArray().apply { releases.forEach { put(it) } }

    /** [count] releases of the other apps (iOS, Android's tests, Windows test builds). */
    private fun others(count: Int, from: Int = 0) = (0 until count).map {
        when (it % 3) {
            0 -> release("ios-${from + it}", asset = "titan.ipa")
            1 -> release("android-test-${from + it}", asset = "titan-vps-universal.apk")
            else -> release("win-test-${from + it}")
        }
    }

    // ------------------------------------------------------------ one page

    @Test
    fun onlyWindowsNumberedReleasesWithTheInstallerCount() {
        val found = Updater.windowsReleases(
            page(
                release("win-test-99"), // a test build: never offered
                release("windows-90", draft = true),
                release("windows-89", asset = "titan-vps.msi"), // no installer
                release("windows-test-7"),
                release("windows-88"),
                release("android-300", asset = "titan-vps-arm64.apk"),
                release("ios-95", asset = "titan.ipa"),
                release("windows-87"),
                release("windows-86beta"),
            )
        )
        assertEquals(listOf(88, 87), found.map { it.build })
        assertEquals("https://example.com/windows-88/titan-vps-setup.exe", found.first().url)
        assertEquals("Titan VPS windows-88", found.first().name)
    }

    @Test
    fun theHighestBuildOnThePageIsOffered() {
        val pages = listOf(page(release("windows-86"), release("windows-91"), release("windows-90")))
        assertEquals(91, Updater.newestWindows { pages[it - 1].toString() }?.build)
    }

    // ------------------------------------------------------------ paging

    @Test
    fun aWindowsReleasePushedOffTheFirstPageIsStillFound() {
        // A busy day of iOS and Android builds: windows-86 is on the second page.
        val first = page(*others(Updater.PER_PAGE).toTypedArray())
        val second = page(*(others(15, from = 500) + release("windows-86") + release("windows-85")).toTypedArray())
        val asked = mutableListOf<Int>()
        val found = Updater.newestWindows { n -> asked += n; listOf(first, second)[n - 1].toString() }
        assertEquals(86, found?.build)
        assertEquals(listOf(1, 2), asked)
    }

    @Test
    fun aPageWithAWindowsReleaseEndsTheSearch() {
        val asked = mutableListOf<Int>()
        val full = page(*(others(Updater.PER_PAGE - 1) + release("windows-86")).toTypedArray())
        assertEquals(86, Updater.newestWindows { n -> asked += n; full.toString() }?.build)
        assertEquals(listOf(1), asked)
    }

    @Test
    fun theLastPageEndsTheSearch() {
        // A short page is the list's last: nothing more is asked for.
        val asked = mutableListOf<Int>()
        val found = Updater.newestWindows { n -> asked += n; page(*others(12).toTypedArray()).toString() }
        assertNull(found)
        assertEquals(listOf(1), asked)
    }

    @Test
    fun atMostThreePagesAreRead() {
        val asked = mutableListOf<Int>()
        val found = Updater.newestWindows { n -> asked += n; page(*others(Updater.PER_PAGE, from = n * 1000).toTypedArray()).toString() }
        assertNull(found)
        assertEquals((1..Updater.MAX_PAGES).toList(), asked)
    }

    @Test(expected = IllegalStateException::class)
    fun anErrorFromGitHubIsNotReadAsNoUpdate() {
        // An HTTP status from GitHub (its limit per address) must not read «У вас последняя версия».
        Updater.newestWindows { error("HTTP 403") }
    }

    // ------------------------------------------------------------ saving the download

    /** What [Updater.save] threw, or null. */
    private fun saveError(input: InputStream, open: () -> OutputStream): Throwable? =
        runCatching { Updater.save(input, open, total = 1024, onProgress = {}) }.exceptionOrNull()

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    @Test
    fun aFullDiskIsADiskErrorNotANetworkOne() {
        // Only an IOException moves the user to АВТО: a full disk must not.
        val full = object : OutputStream() {
            override fun write(b: Int) = throw IOException("There is not enough space on the disk")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("There is not enough space on the disk")
        }
        val e = saveError(ByteArrayInputStream(bytes)) { full }
        assertTrue(e.toString(), e is Updater.DiskError)
        assertFalse(e is IOException)
        assertTrue(e!!.cause is IOException)
    }

    @Test
    fun aFileThatCantBeOpenedIsADiskError() {
        // Locked by another process, or the folder not writable.
        val e = saveError(ByteArrayInputStream(bytes)) { throw FileNotFoundException("locked") }
        assertTrue(e.toString(), e is Updater.DiskError)
        assertFalse(e is IOException)
    }

    @Test
    fun aFailedCloseIsADiskError() {
        // What the file still held goes to the disk on close.
        val out = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
            override fun close() = throw IOException("There is not enough space on the disk")
        }
        assertTrue(saveError(ByteArrayInputStream(bytes)) { out } is Updater.DiskError)
    }

    @Test
    fun aNetworkFailureStaysAnIOException() {
        // GitHub not reachable through the server mid-download: АВТО may still help.
        val dropped = object : InputStream() {
            private var sent = 0
            override fun read(): Int = throw IOException("unexpected end of stream")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent > 0) throw IOException("unexpected end of stream")
                sent = minOf(len, 1000)
                return sent
            }
        }
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        val e = saveError(dropped) { out }
        assertTrue(e.toString(), e is IOException)
        assertFalse(e is Updater.DiskError)
        // The file isn't left open (Windows would keep it locked).
        assertTrue(closed)
    }

    @Test
    fun everyByteIsSavedWithItsProgress() {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Float>()
        Updater.save(ByteArrayInputStream(bytes), { out }, bytes.size.toLong()) { progress += it }
        assertTrue(bytes.contentEquals(out.toByteArray()))
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress)
        // An unknown length: no progress, the file still whole.
        val unknown = ByteArrayOutputStream()
        val none = mutableListOf<Float>()
        Updater.save(ByteArrayInputStream(bytes), { unknown }, -1) { none += it }
        assertTrue(bytes.contentEquals(unknown.toByteArray()))
        assertTrue(none.isEmpty())
    }

    // ------------------------------------------------------------ the update script

    private val script = Updater.updateScript(
        pid = 4242, coreDir = "C:\\Titan\\core", exe = "C:\\Titan\\Titan VPS.exe",
        logo = "C:\\Temp\\titan-logo.png", setup = "C:\\Temp\\titan-vps-setup-90.exe",
    )

    @Test
    fun theScriptGoesOnWhenTheInstallerNeverStarted() {
        // Start-Process gives nothing when Windows refuses the file: "-not $null.HasExited" was
        // true forever, and the window stayed with the app never back.
        assertFalse(script.contains("while (-not \$setup.HasExited)"))
        assertTrue(script.contains("while (\$setup -and -not \$setup.HasExited -and (Get-Date) -lt \$deadline)"))
        // Then the installed app is started again.
        assertTrue(script.contains("if (-not (Get-Process 'Titan VPS')) { Start-Process -FilePath 'C:\\Titan\\Titan VPS.exe' }"))
    }

    @Test
    fun theScriptWaitsAtMostItsDeadline() {
        assertTrue(script.contains("\$deadline = (Get-Date).AddMinutes(${Updater.WAIT_MINUTES})"))
        assertTrue(script.contains("while ((Get-Process -Id 4242 -ErrorAction SilentlyContinue) -and (Get-Date) -lt \$deadline)"))
        // An installer still running then: the window goes, the app isn't started over it.
        val giveUp = script.indexOf("if (\$setup -and -not \$setup.HasExited) { \$form.Close(); exit }")
        assertTrue(giveUp > 0)
        assertTrue(giveUp < script.indexOf("Start-Process -FilePath 'C:\\Titan\\Titan VPS.exe'"))
        // No other wait without an end (the last one is the 45 s for the new version's window).
        val waits = Regex("while \\(([^\\n]*)\\)").findAll(script).map { it.groupValues[1] }.toList()
        assertTrue(waits.isNotEmpty())
        waits.forEach { assertTrue(it, "\$deadline" in it || "\$until" in it || "\$end" in it) }
    }

    @Test
    fun pathsAreQuotedForPowerShell() {
        val s = Updater.updateScript(1, "C:\\Users\\O'Brien\\core", null, "C:\\l.png", "C:\\Users\\O'Brien\\setup.exe")
        assertTrue(s.contains("-FilePath 'C:\\Users\\O''Brien\\setup.exe'"))
        assertTrue(s.contains("-like 'C:\\Users\\O''Brien\\core*'"))
        // A curly apostrophe is a quote mark for PowerShell too.
        val curly = Updater.updateScript(1, "C:\\Users\\O\u2019Brien\\core", null, "C:\\l.png", "C:\\Users\\O\u2019Brien\\setup.exe")
        assertTrue(curly.contains("-FilePath 'C:\\Users\\O\u2019\u2019Brien\\setup.exe'"))
        // No installed exe known: nothing to start by hand.
        assertFalse(s.contains("Get-Process 'Titan VPS')) { Start-Process"))
    }

    // ------------------------------------------------------------ reconnect after the update

    @Test
    fun theReconnectAfterAnUpdateIsSavedAndReadBack() {
        val state = Repository.State(null, null, "dark", reconnectAfterUpdate = true)
        val text = StateJson.encode(state)
        assertTrue(StateJson.decode(text)!!.reconnectAfterUpdate)
        // Cleared once used: the next save leaves it out.
        val cleared = StateJson.encode(state.copy(reconnectAfterUpdate = false))
        assertFalse(JSONObject(cleared).has("reconnectAfterUpdate"))
        assertFalse(StateJson.decode(cleared)!!.reconnectAfterUpdate)
    }

    @Test
    fun aFileFromBeforeHasNoReconnect() {
        val old = StateJson.decode("""{"theme":"system","selected":"","mode":"tun","autoConnect":false}""")
        assertNotNull(old)
        assertFalse(old!!.reconnectAfterUpdate)
        assertEquals("tun", old.mode)
        assertNull(StateJson.decode("not json"))
    }
}
