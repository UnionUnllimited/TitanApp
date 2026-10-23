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
}
