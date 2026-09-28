package com.titanvps.app

import com.titanvps.app.core.TcpPing
import com.titanvps.app.data.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket

class TcpPingTest {

    private fun server(outbound: String, tag: String = "proxy") =
        Server("id", "name", """{"outbounds":[$outbound,{"tag":"direct","protocol":"freedom"}]}""", tag)

    @Test
    fun vnextEndpoint() {
        val s = server("""{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"de.example.com","port":443}]}}""")
        assertEquals("de.example.com" to 443, TcpPing.endpoint(s))
    }

    @Test
    fun serversEndpoint() {
        val s = server("""{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"1.2.3.4","port":8443}]}}""")
        assertEquals("1.2.3.4" to 8443, TcpPing.endpoint(s))
    }

    @Test
    fun flatEndpoint() {
        val s = server("""{"tag":"proxy","protocol":"vless","settings":{"address":"nl.example.com","port":2053}}""")
        assertEquals("nl.example.com" to 2053, TcpPing.endpoint(s))
    }

    @Test
    fun missingEndpoint() {
        assertNull(TcpPing.endpoint(server("""{"tag":"proxy","protocol":"vless","settings":{}}""")))
        assertNull(TcpPing.endpoint(server("""{"tag":"other","protocol":"vless"}""")))
    }

    @Test
    fun pingsLocalSocket() {
        ServerSocket(0).use { ss ->
            val d = TcpPing.ping("127.0.0.1", ss.localPort, 2000)
            assert(d >= 0) { "expected reachable, got $d" }
        }
        assertEquals(-1L, TcpPing.ping("127.0.0.1", 1, 500))
    }
}
