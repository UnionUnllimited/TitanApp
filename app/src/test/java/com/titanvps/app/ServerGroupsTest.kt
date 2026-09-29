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
    fun everythingAfterFirstBypassIsBypass() {
        val all = listOf(s("🇦🇱 БЕЗЛИМИТНЫЕ"), s("🇫🇮 Финляндия"), s("🇷🇺 Обход 1"), s("🇷🇺 Обход 2"),
            s("🇦🇱 ⚡ АВТО | Самые быстрые"), s("🇦🇱 СЕРВЕР ДЛЯ"), s("🇦🇱 ОБНОВЛЕНИЯ ПОДПИСКИ"))
        val groups = ServerGroups.split(all).toMap()
        assertEquals(listOf("🇦🇱 БЕЗЛИМИТНЫЕ", "🇫🇮 Финляндия"), groups[Group.SERVERS]!!.map { it.name })
        assertEquals(5, groups[Group.BYPASS]!!.size)
        assertEquals(Group.BYPASS, ServerGroups.groupOf(all.last(), all))
        assertEquals(Group.SERVERS, ServerGroups.groupOf(all[1], all))
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
