package com.titanvps.app

import com.titanvps.app.vpn.KeepAlive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveTest {

    @Test
    fun revivesOnlyWhenOnAndWanted() {
        assertTrue(KeepAlive.shouldRevive(keepAlive = true, wanted = true))
        // Off by hand: stays off.
        assertFalse(KeepAlive.shouldRevive(keepAlive = true, wanted = false))
        // «Не отключать VPN» switched off: a killed VPN isn't brought back.
        assertFalse(KeepAlive.shouldRevive(keepAlive = false, wanted = true))
        assertFalse(KeepAlive.shouldRevive(keepAlive = false, wanted = false))
    }

    @Test
    fun backoffGrowsToHalfAMinute() {
        // As on iOS: 1, 2, 4, 8, 15, 30 s, then every 30 s.
        assertEquals(listOf(1, 2, 4, 8, 15, 30), KeepAlive.BACKOFF_SEC)
        assertEquals(KeepAlive.BACKOFF_SEC.sorted(), KeepAlive.BACKOFF_SEC)
        assertEquals(30, KeepAlive.BACKOFF_SEC.last())
    }
}
