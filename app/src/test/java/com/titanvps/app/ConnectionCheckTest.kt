package com.titanvps.app

import com.titanvps.app.core.ConnectionCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionCheckTest {

    @Test
    fun traceWithIpAndCountry() {
        val body = "fl=29f12\nh=1.1.1.1\nip=203.0.113.7\nts=1700000000.1\nvisit_scheme=https\nloc=fi\ntls=TLSv1.3\n"
        assertEquals("203.0.113.7" to "FI", ConnectionCheck.parseTrace(body))
    }

    @Test
    fun traceWithoutThem() {
        assertEquals(null to null, ConnectionCheck.parseTrace("fl=29f12\nh=1.1.1.1\n"))
        assertEquals(null to null, ConnectionCheck.parseTrace(""))
        // Empty values count as missing.
        val (ip, loc) = ConnectionCheck.parseTrace("ip=\nloc=\n")
        assertNull(ip)
        assertNull(loc)
    }

    @Test
    fun traceWithCrLf() {
        val body = "ip=2001:db8::1\r\nloc=DE\r\nwarp=off\r\n"
        assertEquals("2001:db8::1" to "DE", ConnectionCheck.parseTrace(body))
    }

    @Test
    fun valueKeepsItsEqualsSigns() {
        assertEquals("a=b" to "NL", ConnectionCheck.parseTrace("ip=a=b\nloc=nl"))
    }

    @Test
    fun medianOfTheSamples() {
        // One slow answer among warm ones doesn't move it.
        assertEquals(64L, ConnectionCheck.median(listOf(1575, 64, 61)))
        assertEquals(70L, ConnectionCheck.median(listOf(80, 60)))
        assertEquals(42L, ConnectionCheck.median(listOf(42)))
        assertNull(ConnectionCheck.median(emptyList()))
    }

    @Test
    fun latencyShownCalmly() {
        assertEquals(ConnectionCheck.Latency.FAST, ConnectionCheck.latencyOf(1))
        assertEquals(ConnectionCheck.Latency.FAST, ConnectionCheck.latencyOf(119))
        assertEquals(ConnectionCheck.Latency.NORMAL, ConnectionCheck.latencyOf(120))
        assertEquals(ConnectionCheck.Latency.NORMAL, ConnectionCheck.latencyOf(299))
        // From 300 ms a word instead of the number.
        assertEquals(ConnectionCheck.Latency.SLOW, ConnectionCheck.latencyOf(300))
        assertEquals(ConnectionCheck.Latency.SLOW, ConnectionCheck.latencyOf(1575))
    }
}
