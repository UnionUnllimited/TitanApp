package com.titanvps.app

import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerOrderTest {

    private fun s(id: String, name: String) = Server(id, name, xrayJson = "{}", proxyTag = "proxy")

    private val fi = s("fi", "🇫🇮 Финляндия")
    private val de = s("de", "🇩🇪 Германия")
    private val nl = s("nl", "🇳🇱 Нидерланды")
    private val b1 = s("b1", "🇷🇺 Обход 1")
    private val b2 = s("b2", "🇷🇺 Обход 2")
    private val all = listOf(fi, b1, de, b2, nl)

    @Test
    fun favouritesFirstInSubscriptionOrder() {
        assertEquals(listOf(de, nl, fi), ServerOrder.ordered(listOf(fi, de, nl), setOf("🇳🇱 Нидерланды", "🇩🇪 Германия")))
        assertEquals(listOf(fi, de, nl), ServerOrder.ordered(listOf(fi, de, nl), emptySet()))
    }

    @Test
    fun stepsWithinItsGroupRoundTheEnd() {
        assertEquals(de, ServerOrder.neighbor(all, "fi", forward = true, favorites = emptySet(), wifiLocked = false))
        assertEquals(fi, ServerOrder.neighbor(all, "nl", forward = true, favorites = emptySet(), wifiLocked = false))
        assertEquals(nl, ServerOrder.neighbor(all, "fi", forward = false, favorites = emptySet(), wifiLocked = false))
        assertEquals(b2, ServerOrder.neighbor(all, "b1", forward = true, favorites = emptySet(), wifiLocked = false))
        assertEquals(b2, ServerOrder.neighbor(all, "b1", forward = false, favorites = emptySet(), wifiLocked = false))
        assertEquals(b1, ServerOrder.neighbor(all, "b2", forward = true, favorites = emptySet(), wifiLocked = false))
    }

    @Test
    fun favouritesComeFirstWhenStepping() {
        val favorites = setOf("🇳🇱 Нидерланды")
        assertEquals(fi, ServerOrder.neighbor(all, "nl", forward = true, favorites = favorites, wifiLocked = false))
        assertEquals(nl, ServerOrder.neighbor(all, "de", forward = true, favorites = favorites, wifiLocked = false))
    }

    @Test
    fun aGroupOfOneHasNoNeighbour() {
        val servers = listOf(fi, b1)
        assertNull(ServerOrder.neighbor(servers, "fi", forward = true, favorites = emptySet(), wifiLocked = false))
        assertNull(ServerOrder.neighbor(servers, "b1", forward = false, favorites = emptySet(), wifiLocked = false))
    }

    @Test
    fun bypassOnWifiJumpsToRegular() {
        assertEquals(fi, ServerOrder.neighbor(all, "b1", forward = true, favorites = emptySet(), wifiLocked = true))
        assertEquals(nl, ServerOrder.neighbor(all, "b1", forward = false, favorites = emptySet(), wifiLocked = true))
    }

    @Test
    fun unknownCurrentStartsAtRegular() {
        assertEquals(fi, ServerOrder.neighbor(all, "gone", forward = true, favorites = emptySet(), wifiLocked = false))
        assertEquals(nl, ServerOrder.neighbor(all, null, forward = false, favorites = emptySet(), wifiLocked = false))
        assertNull(ServerOrder.neighbor(emptyList(), null, forward = true, favorites = emptySet(), wifiLocked = false))
    }
}
