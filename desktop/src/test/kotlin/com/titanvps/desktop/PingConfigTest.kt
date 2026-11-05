package com.titanvps.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The ping core's config and the host each probe dials first (Android's PingConfigTest, on XrayConfigs). */
class PingConfigTest {

    @Test
    fun socksInboundPerServerRoutedToItsProxy() {
        val a = """{"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}]}"""
        val b = """{"outbounds":[{"tag":"proxy","protocol":"hysteria","streamSettings":{"sockopt":{"dialerProxy":"frag"}}},{"tag":"frag","protocol":"freedom"}]}"""
        val cfg = JSONObject(XrayConfigs.buildPingConfig(listOf(a to "proxy", b to "proxy"), listOf(20001, 20002)))

        val inbounds = cfg.getJSONArray("inbounds")
        assertEquals(2, inbounds.length())
        assertEquals(20002, inbounds.getJSONObject(1).getInt("port"))

        val tags = (0 until cfg.getJSONArray("outbounds").length()).map { cfg.getJSONArray("outbounds").getJSONObject(it).getString("tag") }
        assertEquals(listOf("block", "p0-proxy", "p1-proxy", "p1-frag"), tags)
        val p1 = cfg.getJSONArray("outbounds").getJSONObject(2)
        assertEquals("p1-frag", p1.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))

        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        assertEquals("p1-proxy", rules.getJSONObject(1).getString("outboundTag"))
        assertEquals("in1", rules.getJSONObject(1).getJSONArray("inboundTag").getString(0))
    }

    @Test
    fun theHostAProbeDialsFirst() {
        // Its own server, lower case.
        val vless = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"A.Example","port":443,"users":[{"id":"u"}]}]}}]}"""
        assertEquals("a.example", XrayConfigs.dialHost(vless, "proxy"))
        // A chain: its first hop (the entry), past a fragment's freedom that has no server.
        val chain = """{"outbounds":[
            {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"exit.example","port":443}]},"streamSettings":{"sockopt":{"dialerProxy":"entry"}}},
            {"tag":"entry","protocol":"shadowsocks","settings":{"servers":[{"address":"192.0.2.10","port":8388}]},"streamSettings":{"sockopt":{"dialerProxy":"frag"}}},
            {"tag":"frag","protocol":"freedom","settings":{"fragment":{"packets":"tlshello"}}}]}"""
        assertEquals("192.0.2.10", XrayConfigs.dialHost(chain, "proxy"))
        assertEquals("192.0.2.10", XrayConfigs.dialHost(chain, "entry"))
        // The address in the settings themselves (Hysteria 2; MASQUE as rewritten).
        val flat = """{"outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"address":"b.example","port":443}}]}"""
        assertEquals("b.example", XrayConfigs.dialHost(flat, "proxy"))
        // None: a protocol client's local SOCKS, a loop without a server, no such outbound.
        val local = """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}}]}"""
        assertNull(XrayConfigs.dialHost(local, "proxy"))
        val loop = """{"outbounds":[{"tag":"a","protocol":"freedom","streamSettings":{"sockopt":{"dialerProxy":"b"}}},{"tag":"b","protocol":"freedom","streamSettings":{"sockopt":{"dialerProxy":"a"}}}]}"""
        assertNull(XrayConfigs.dialHost(loop, "a"))
        assertNull(XrayConfigs.dialHost(vless, "other"))
        assertNull(XrayConfigs.dialHost("not json", "proxy"))
    }

    @Test
    fun aProtocolClientsProbeIsHeldToItsRealServer() {
        // A Naive host (and a balancer member beside it): the probe goes through the client's
        // local SOCKS, yet the host it is held to is the server the client dials.
        val naive = Server(
            "n", "Германия Naive",
            """{"outbounds":[
                {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"N.Example","port":443,"users":[{"id":"s"}]}]}},
                {"tag":"m2","protocol":"vless","settings":{"vnext":[{"address":"198.51.100.7","port":443,"users":[{"id":"u"}]}]}}]}""",
            "proxy",
        )
        val client = Plugins.endpoint(naive)
        assertNotNull(client)
        assertEquals("n.example", XrayConfigs.entryHost(naive, "proxy", client))
        // Not what the prepared config dials (this PC: no rule).
        assertNull(XrayConfigs.dialHost(Plugins.withLocalSocks(naive.xrayJson, "proxy", 1080), "proxy"))
        // Another member keeps its own server.
        assertEquals("198.51.100.7", XrayConfigs.entryHost(naive, "m2", client))

        // A client replaces the outbound with its chain: it dials its server itself. Not prepared
        // (no client), the outbound's own chain as Xray dials it.
        val chained = Server(
            "c", "Mieru",
            """{"outbounds":[
                {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"m.example","port":443,"users":[{"id":"s"}]}]},"streamSettings":{"sockopt":{"dialerProxy":"entry"}}},
                {"tag":"entry","protocol":"shadowsocks","settings":{"servers":[{"address":"192.0.2.10","port":8388}]}}]}""",
            "proxy",
        )
        assertEquals("m.example", XrayConfigs.entryHost(chained, "proxy", Plugins.endpoint(chained)))
        assertEquals("192.0.2.10", XrayConfigs.entryHost(chained, "proxy", null))

        // A client whose server is this PC: no rule.
        val loopback = Server(
            "l", "Naive",
            """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":443,"users":[{"id":"s"}]}]}}]}""",
            "proxy",
        )
        assertNull(XrayConfigs.entryHost(loopback, "proxy", Plugins.endpoint(loopback)))
    }
}
