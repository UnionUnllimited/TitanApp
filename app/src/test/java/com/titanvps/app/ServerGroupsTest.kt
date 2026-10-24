package com.titanvps.app

import com.titanvps.app.data.Flags
import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.ServerGroups.Group
import com.titanvps.app.data.ServerOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerGroupsTest {

    private fun s(name: String) = Server(name, name, "{}", "proxy")

    @Test
    fun splitsBypassServers() {
        val groups = ServerGroups.split(listOf(s("🇩🇪 Германия 1"), s("🇩🇪 Megafon | Yota Обходы LTE"), s("🇸🇪 Все операторы ОБХОДЫ")))
        assertEquals(listOf(Group.SERVERS, Group.BYPASS), groups.map { it.first })
        assertEquals(1, groups[0].second.size)
        assertEquals(2, groups[1].second.size)
    }

    @Test
    fun onlyObhodNamesAreBypass() {
        val all = listOf(s("🇫🇮 Финляндия"), s("🇷🇺 Обход 1"), s("🇦🇱 ⚡ АВТО | Быстрые обходы"),
            s("🇱🇻 Латвия Naive"), s("🇷🇺 Обход 2"))
        val groups = ServerGroups.split(all).toMap()
        assertEquals(listOf("🇫🇮 Финляндия", "🇱🇻 Латвия Naive"), groups[Group.SERVERS]!!.map { it.name })
        assertEquals(listOf("🇷🇺 Обход 1", "🇦🇱 ⚡ АВТО | Быстрые обходы", "🇷🇺 Обход 2"), groups[Group.BYPASS]!!.map { it.name })
        assertEquals(Group.SERVERS, ServerGroups.groupOf(all[3], all))
        assertEquals(Group.BYPASS, ServerGroups.groupOf(all[2], all))
    }

    @Test
    fun headersAndDummyEntriesAreHidden() {
        assert(ServerGroups.isHidden(s("🇦🇱 👇БЕЗЛИМИТНЫЕ👇")))
        assert(ServerGroups.isHidden(s("🇦🇱 👇ЛИМИТНЫЕ ОСТ:1004.00 GB")))
        assert(!ServerGroups.isHidden(s("🇩🇪 Германия 1")))
        assert(ServerGroups.isHidden(s("🇦🇱 СЕРВЕР ДЛЯ")))
        assert(ServerGroups.isHidden(s("🇦🇱 ОБНОВЛЕНИЯ ПОДПИСКИ")))
        val dummy = Server("x", "🇦🇱 СЕРВЕР ДЛЯ", """{"outbounds":[{"tag":"proxy","protocol":"vless",
            "settings":{"vnext":[{"address":"127.0.0.1","port":1,"users":[{"id":"0"}]}]}}]}""", "proxy")
        assert(ServerGroups.isHidden(dummy))
        val real = Server("y", "🇦🇱 Албания", """{"outbounds":[{"tag":"proxy","protocol":"vless",
            "settings":{"vnext":[{"address":"node.example","port":443,"users":[{"id":"0"}]}]}}]}""", "proxy")
        assert(!ServerGroups.isHidden(real))
    }

    /** A made-up balancer member: the row's proxy outbound with a real (fake) address. */
    private val unlimitedJson = """{"outbounds":[{"tag":"bal-1","protocol":"vless",
        "settings":{"vnext":[{"address":"node.example","port":443,"users":[{"id":"0"}]}]}}]}"""

    @Test
    fun unlimitedRowIsARegularServer() {
        val raw = Server("u", "🇦🇱 👇БЕЗЛИМИТНЫЕ👇", unlimitedJson, "bal-1")
        assertTrue(ServerGroups.isUnlimitedRow(raw))
        val unlimited = raw.copy(name = ServerGroups.UNLIMITED_NAME)
        assertTrue(ServerGroups.isUnlimited(unlimited))
        assertFalse(ServerGroups.isHidden(unlimited))
        assertEquals(Group.SERVERS, ServerGroups.groupOf(unlimited))
        assertFalse(ServerGroups.isLimited(unlimited))
        // The bolt, not the 🇦🇱 placeholder flag.
        assertNull(Flags.codeOf(unlimited.name))
        // First as parsed; the user's favourites still go above it.
        val de = s("🇩🇪 Германия 1")
        val fi = s("🇫🇮 Финляндия")
        val regular = ServerGroups.split(listOf(unlimited, de, s("🇷🇺 Обход 1"), fi)).toMap().getValue(Group.SERVERS)
        assertEquals(listOf(unlimited, de, fi), ServerOrder.ordered(regular, emptySet()))
        assertEquals(listOf(fi, unlimited, de), ServerOrder.ordered(regular, setOf(fi.name)))
    }

    @Test
    fun headersWithoutARealConfigStayHidden() {
        assertFalse(ServerGroups.isUnlimitedRow(s("🇦🇱 👇БЕЗЛИМИТНЫЕ👇")))
        val dummy = Server("x", "🇦🇱 👇БЕЗЛИМИТНЫЕ👇", """{"outbounds":[{"tag":"proxy","protocol":"vless",
            "settings":{"vnext":[{"address":"0.0.0.0","port":443,"users":[{"id":"0"}]}]}}]}""", "proxy")
        assertFalse(ServerGroups.isUnlimitedRow(dummy))
        assertTrue(ServerGroups.isHidden(dummy))
        val limited = Server("l", "🇦🇱 👇ЛИМИТНЫЕ ОСТ:1004.00 GB", unlimitedJson, "bal-1")
        assertFalse(ServerGroups.isUnlimitedRow(limited))
        assertTrue(ServerGroups.isHidden(limited))
        assertTrue(ServerGroups.isHidden(Server("n", "🇦🇱 СЕРВЕР ДЛЯ безлимитных", unlimitedJson, "bal-1")))
        // The address straight in the settings (hysteria) counts as well.
        val hy = Server("h", "👇 Безлимитные 👇", """{"outbounds":[{"tag":"hy","protocol":"hysteria",
            "settings":{"version":2,"address":"hy.example","port":443}}]}""", "hy")
        assertTrue(ServerGroups.isUnlimitedRow(hy))
    }

    @Test
    fun tabsSayWhereTheyWork() {
        assertEquals("Страны", Group.SERVERS.title)
        assertEquals("Wi‑Fi / LTE", Group.SERVERS.networks)
        assertEquals("Страны, 29 серверов, работают на Wi-Fi и LTE", ServerGroups.tabDescription(Group.SERVERS, 29))
        assertEquals("Обходы, 57 серверов, только LTE", ServerGroups.tabDescription(Group.BYPASS, 57))
        assertEquals("Обходы, 1 сервер, только LTE", ServerGroups.tabDescription(Group.BYPASS, 1))
    }

    @Test
    fun emptyGroupsAreHidden() {
        assertEquals(listOf(Group.SERVERS), ServerGroups.split(listOf(s("Финляндия"))).map { it.first })
    }

    @Test
    fun extractsFlag() {
        assertEquals("🇫🇮" to "🚀Финляндия 1", ServerGroups.splitFlag("🇫🇮 🚀Финляндия 1"))
        val (flag, rest) = ServerGroups.splitFlag("⚡ АВТО")
        assertNull(flag)
        assertEquals("⚡ АВТО", rest)
    }
}
