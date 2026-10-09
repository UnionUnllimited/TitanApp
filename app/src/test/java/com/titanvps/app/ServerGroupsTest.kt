package com.titanvps.app

import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.ServerGroups.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
