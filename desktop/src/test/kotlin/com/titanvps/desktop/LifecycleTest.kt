package com.titanvps.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread

/**
 * The connection's life on Windows: who owns the processes (a connect overtaken by Выкл or
 * another pick), bringing back an exited core, the elevated logon task, the system proxy's
 * WinINet layout, who can be raised to administrator.
 */
class LifecycleTest {

    // ------------------------------------------------------------ ConnectionGen

    @Test
    fun anOffDuringAConnectsStepWaitsForItAndTheConnectGivesUpAfter() {
        val conn = ConnectionGen()
        val connect = conn.next()
        val log = Collections.synchronizedList(mutableListOf<String>())
        val inStep = CountDownLatch(1)
        val finishStep = CountDownLatch(1)
        val connecting = thread {
            conn.ifCurrent(connect) {
                log += "core starting"
                inStep.countDown()
                finishStep.await()
                log += "core started"
            }
            // Its next step: overtaken, nothing more is started.
            conn.ifCurrent(connect) { log += "proxy on" }
        }
        assertTrue(inStep.await(5, TimeUnit.SECONDS))
        // Выкл while the core is starting.
        val off = conn.next()
        assertFalse(conn.isCurrent(connect))
        val stopping = thread { conn.ifCurrent(off) { log += "all off" } }
        Thread.sleep(150)
        // The off doesn't land in the middle of the step.
        assertEquals(listOf("core starting"), log.toList())
        finishStep.countDown()
        connecting.join(5000)
        stopping.join(5000)
        assertEquals(listOf("core starting", "core started", "all off"), log.toList())
    }

    @Test
    fun anOffQueuedBehindANewerConnectIsSkipped() {
        val conn = ConnectionGen()
        val off = conn.next()
        // Вкл again before the off got its turn: that connect replaces everything itself.
        val connect = conn.next()
        assertNull(conn.ifCurrent(off) { "stopped" })
        assertEquals("started", conn.ifCurrent(connect) { "started" })
    }

    @Test
    fun theFinalShutdownRunsWhoeverOwns() {
        val conn = ConnectionGen()
        val old = conn.next()
        conn.next()
        assertNull(conn.ifCurrent(old) { 1 })
        assertEquals(2, conn.locked { 2 })
    }

    // ------------------------------------------------------------ Revive

    @Test
    fun anExitedCoreIsTriedAtOnceThenAfterGrowingPausesAndNotForever() {
        val waits = generateSequence(0) { it + 1 }.map { Revive.waitSec(it) }.takeWhile { it != null }.toList()
        assertEquals(listOf(0, 1, 2, 4, 8, 15), waits)
        // About half a minute in all, then the VPN goes off with an error.
        assertTrue(waits.sumOf { it!! } <= 30)
    }

    @Test
    fun aCoreThatKeepsExitingSoonAfterEachRestartEndsInAnError() {
        var tries = 0
        var restarts = 0
        while (true) {
            // Up only 5 s each time.
            tries = Revive.triesAfterExit(tries, upMs = 5_000)
            Revive.waitSec(tries++) ?: break
            restarts++
        }
        assertEquals(Revive.WAIT_SEC.size, restarts)
    }

    @Test
    fun aConnectionUpAMinuteAgainCountsItsTriesFromZero() {
        assertEquals(0, Revive.triesAfterExit(5, Revive.STABLE_MS))
        assertEquals(5, Revive.triesAfterExit(5, Revive.STABLE_MS - 1))
    }

    // ------------------------------------------------------------ PluginProcess

    @Test
    fun aClosedProtocolClientIsNeverStartedAgain() {
        val p = PluginProcess(Plugins.Endpoint(Plugins.Kind.NAIVE, "example.com", 443, "secret"))
        p.close()
        assertFalse(p.isRunning)
        assertNull(p.exits())
        try {
            p.start()
            fail("a closed client started")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    // ------------------------------------------------------------ TUN kept on a server switch

    @Test
    fun theSameTunnelSettingsGiveTheSameConfigSoSingBoxIsKept() {
        val apps = linkedSetOf("game.exe", "torrent.exe")
        val a = TunConfig.build(20001, apps, only = false, logDir = "C:/logs")
        val b = TunConfig.build(20001, linkedSetOf("game.exe", "torrent.exe"), only = false, logDir = "C:/logs")
        assertEquals(a, b)
        // Another SOCKS port or other programs: a new tunnel.
        assertNotEquals(a, TunConfig.build(20002, apps, only = false, logDir = "C:/logs"))
        assertNotEquals(a, TunConfig.build(20001, apps + "browser.exe", only = false, logDir = "C:/logs"))
    }

    // ------------------------------------------------------------ Autostart

    private val exe = "C:\\Users\\Test\\AppData\\Local\\Programs\\Titan VPS\\Titan VPS.exe"

    private fun settings(xml: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        val nodes = doc.getElementsByTagNameNS("*", "Settings").item(0).childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            .associate { it.localName to it.textContent }
    }

    @Test
    fun theLogonTaskRunsOnBatteryWithoutATimeLimitAtNormalPriority() {
        val xml = Autostart.taskXml(exe)
        val s = settings(xml)
        assertEquals("false", s["DisallowStartIfOnBatteries"])
        assertEquals("false", s["StopIfGoingOnBatteries"])
        assertEquals("PT0S", s["ExecutionTimeLimit"])
        assertEquals("4", s["Priority"])
        assertTrue("<RunLevel>HighestAvailable</RunLevel>" in xml)
        assertTrue("<LogonTrigger>" in xml)
        assertTrue("<Command>\"$exe\"</Command>" in xml)
        assertTrue("<Arguments>--autostart</Arguments>" in xml)
        assertTrue(Autostart.isCurrent(xml))
    }

    @Test
    fun aPathWithXmlCharactersStaysWellFormed() {
        val xml = Autostart.taskXml("C:\\Users\\Tom & <Jerry>\\Titan VPS.exe")
        assertEquals("4", settings(xml)["Priority"])
        assertTrue("Tom &amp; &lt;Jerry&gt;" in xml)
    }

    @Test
    fun aTaskWithSchtasksDefaultsIsRepaired() {
        // What `schtasks /create /sc onlogon /rl highest` makes, as `schtasks /query /xml` prints it.
        val old = """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>true</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>true</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <ExecutionTimeLimit>PT72H</ExecutionTimeLimit>
    <Priority>7</Priority>
  </Settings>
</Task>"""
        assertFalse(Autostart.isCurrent(old))
    }

    @Test
    fun schtasksOutputIsReadInUtf16AndInTheConsolesCodePage() {
        val xml = Autostart.taskXml(exe)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xml.replace("\n", "\r\n").toByteArray(Charsets.UTF_16LE)
        assertTrue(Autostart.isCurrent(Autostart.textOf(utf16)))
        assertTrue(Autostart.isCurrent(Autostart.textOf(xml.toByteArray(Charsets.US_ASCII))))
    }

    // ------------------------------------------------------------ System proxy

    @Test
    fun theConnectionsFlagsAreItsThirdDword() {
        // Version, change counter, flags (direct + automatic detection, Windows' default), …
        val blob = byteArrayOf(0x46, 0, 0, 0, 0x2A, 0, 0, 0, 0x09, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(SystemProxy.PROXY_TYPE_DIRECT or SystemProxy.PROXY_TYPE_AUTO_DETECT, SystemProxy.flagsOf(blob))
        assertEquals(0x0D, SystemProxy.flagsOf(byteArrayOf(0x46, 0, 0, 0, 1, 0, 0, 0, 0x0D, 0, 0, 0)))
        assertNull(SystemProxy.flagsOf(byteArrayOf(0x46, 0, 0, 0)))
        assertNull(SystemProxy.flagsOf(null))
    }

    @Test
    fun perConnectionOptionsAreLaidOutAsWininetExpects() {
        // 64-bit: INTERNET_PER_CONN_OPTIONW is 16 bytes (DWORD, padding, 8-byte union);
        // the list is dwSize, padding, pszConnection, two DWORDs, pOptions = 32.
        assertEquals(
            SystemProxy.PerConnLayout(optionSize = 16, valueOffset = 8, listSize = 32, connectionOffset = 8, countOffset = 16, optionsOffset = 24),
            SystemProxy.PerConnLayout.of(8),
        )
        // 32-bit: 12 and 20.
        assertEquals(
            SystemProxy.PerConnLayout(optionSize = 12, valueOffset = 4, listSize = 20, connectionOffset = 4, countOffset = 8, optionsOffset = 16),
            SystemProxy.PerConnLayout.of(4),
        )
    }

    private val pppoe = "Высокоскоростное подключение"

    @Test
    fun aDialUpOrVpnConnectionIsSavedBeforeItIsChanged() {
        val ras = JSONArray()
        val read = mutableListOf<String>()
        val before = mapOf(
            pppoe to SystemProxy.Settings(SystemProxy.PROXY_TYPE_DIRECT or SystemProxy.PROXY_TYPE_AUTO_DETECT, "", "", ""),
            "Work VPN" to SystemProxy.Settings(SystemProxy.PROXY_TYPE_DIRECT or SystemProxy.PROXY_TYPE_AUTO_PROXY_URL, "", "<local>", "http://wpad.test/proxy.pac"),
        )
        val ours = SystemProxy.takeRas(ras, listOf(pppoe, "Work VPN", "Unreadable", pppoe)) { read += it; before[it] }
        // Each one read once; one Windows didn't say about is left alone (restore couldn't put it back).
        assertEquals(listOf(pppoe, "Work VPN"), ours)
        assertEquals(listOf(pppoe, "Work VPN", "Unreadable"), read)
        assertEquals(before, SystemProxy.savedRas(JSONObject().put("ras", ras)))

        // The next connect (another server): already saved, so what is there now (ours) isn't read
        // over it; a connection added meanwhile is saved too.
        read.clear()
        val next = SystemProxy.takeRas(ras, listOf(pppoe, "Work VPN", "New VPN")) { read += it; SystemProxy.Settings(SystemProxy.PROXY_TYPE_DIRECT, "", "", "") }
        assertEquals(listOf(pppoe, "Work VPN", "New VPN"), next)
        assertEquals(listOf("New VPN"), read)
        val saved = SystemProxy.savedRas(JSONObject().put("ras", ras))
        assertEquals(before[pppoe], saved[pppoe])
        assertEquals(3, saved.size)
    }

    @Test
    fun anOlderBackupHasNoDialUpConnections() {
        assertTrue(SystemProxy.savedRas(SystemProxy.backupOf(0, "", "", "", 9)).isEmpty())
        assertTrue(SystemProxy.savedRas(JSONObject("""{"enable":0,"server":"","override":"","pac":""}""")).isEmpty())
    }

    /** The installer's BackupValue (titan.iss): the first "key": in the text, a string or a number. */
    private fun installerValue(json: String, key: String): String {
        var i = json.indexOf("\"$key\":")
        if (i < 0) return ""
        i += key.length + 3
        val out = StringBuilder()
        if (i < json.length && json[i] == '"') {
            i++
            while (i < json.length && json[i] != '"') {
                if (json[i] == '\\' && i < json.length - 1) i++
                out.append(json[i])
                i++
            }
        } else {
            while (i < json.length && json[i] != ',' && json[i] != '}') out.append(json[i++])
        }
        return out.toString()
    }

    @Test
    fun theInstallerReadsTheLanValuesWhateverTheDialUpConnectionsHold() {
        val backup = SystemProxy.backupOf(1, "proxy.lan.test:3128", "<local>", "http://lan.test/proxy.pac", 0x0B)
        val ras = JSONArray().also { backup.put("ras", it) }
        // Connections named like the keys, with settings of their own.
        for (name in listOf("server", "enable", "override", "pac")) {
            SystemProxy.takeRas(ras, listOf(name)) { SystemProxy.Settings(3, "\"server\":\"x\"", "dial.test", "http://dial.test/pac") }
        }
        // Whatever order the file has its keys in: none of those names inside the connections' list.
        for (key in listOf("enable", "server", "override", "pac")) assertFalse(key, ras.toString().contains("\"$key\":"))
        val text = backup.toString()
        assertEquals("1", installerValue(text, "enable"))
        assertEquals("proxy.lan.test:3128", installerValue(text, "server"))
        assertEquals("<local>", installerValue(text, "override"))
        assertEquals("http://lan.test/proxy.pac", installerValue(text, "pac"))
        assertEquals(4, SystemProxy.savedRas(JSONObject(text)).size)
    }

    /** RasEnumEntriesW's buffer: [names] as RASENTRYNAMEW of [size] bytes. */
    private fun rasBuffer(size: Int, names: List<String>): ByteArray {
        val bytes = ByteArray(size * names.size) { 0x5A } // what Windows leaves after each name
        names.forEachIndexed { i, name ->
            val at = i * size
            java.nio.ByteBuffer.wrap(bytes, at, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(size)
            val text = name.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
            text.copyInto(bytes, at + 4)
        }
        return bytes
    }

    @Test
    fun dialUpConnectionsAreReadFromTheirEntries() {
        for (size in SystemProxy.RAS_ENTRY_SIZES) {
            val names = listOf(pppoe, "Work VPN", "L2TP (дом)")
            assertEquals(names, SystemProxy.rasNames(rasBuffer(size, names), names.size, size))
        }
        // The longest name Windows allows (256) still ends inside its entry.
        val long = "Я".repeat(256)
        assertEquals(listOf(long, "B"), SystemProxy.rasNames(rasBuffer(1048, listOf(long, "B")), 2, 1048))
        // Fewer bytes than said: only whole names.
        assertEquals(listOf(pppoe), SystemProxy.rasNames(rasBuffer(1048, listOf(pppoe)), 2, 1048))
        assertEquals(emptyList<String>(), SystemProxy.rasNames(ByteArray(0), 0, 1048))
    }

    // ------------------------------------------------------------ Autostart

    @Test
    fun aRunKeyTurnedOffInWindowsIsOff() {
        fun approved(first: Int) = byteArrayOf(first.toByte(), 0, 0, 0, 0x10, 0x2F, 0x41, 0x7B, 0x33, 0x12, 0xDA.toByte(), 0x01)
        // Task Manager / Settings > Apps > Startup: off.
        assertTrue(Autostart.offInWindows(approved(0x03)))
        assertTrue(Autostart.offInWindows(approved(0x07)))
        // On there, or never touched there.
        assertFalse(Autostart.offInWindows(approved(0x02)))
        assertFalse(Autostart.offInWindows(approved(0x06)))
        assertFalse(Autostart.offInWindows(null))
        assertFalse(Autostart.offInWindows(ByteArray(0)))
    }

    // ------------------------------------------------------------ PowerShell quoting

    @Test
    fun everyQuoteMarkPowerShellKnowsIsDoubled() {
        assertEquals("'C:\\Users\\Ivan\\x'", psQuoted("C:\\Users\\Ivan\\x"))
        assertEquals("'C:\\Users\\O''Brien'", psQuoted("C:\\Users\\O'Brien"))
        // Curly ones too: PowerShell ends a single-quoted string at them as well.
        assertEquals("'C:\\Users\\O\u2019\u2019Brien'", psQuoted("C:\\Users\\O\u2019Brien"))
        assertEquals("'\u2018\u2018a\u201A\u201Ab\u201B\u201B'", psQuoted("\u2018a\u201Ab\u201B"))
    }

    // ------------------------------------------------------------ Restart as administrator

    @Test
    fun onlyAnAdministratorUnderUacIsOfferedTheRestart() {
        // A standard account: UAC would ask for another account's password.
        assertFalse(Admin.canElevate(admin = false, type = 1))
        // An administrator under UAC: «Да» is enough.
        assertTrue(Admin.canElevate(admin = false, type = 3))
        // Already elevated, or an administrator with UAC off.
        assertTrue(Admin.canElevate(admin = true, type = 2))
        assertTrue(Admin.canElevate(admin = true, type = 1))
        // Windows didn't say: as before.
        assertTrue(Admin.canElevate(admin = false, type = null))
    }
}
