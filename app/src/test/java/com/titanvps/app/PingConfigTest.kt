package com.titanvps.app

import com.titanvps.app.core.PingConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingConfigTest {

    @Test
    fun socksInboundPerServerRoutedToItsProxy() {
        val a = """{"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}]}"""
        val b = """{"outbounds":[{"tag":"proxy","protocol":"hysteria","streamSettings":{"sockopt":{"dialerProxy":"frag"}}},{"tag":"frag","protocol":"freedom"}]}"""
        val cfg = JSONObject(PingConfig.build(listOf(PingConfig.Item(a, "proxy"), PingConfig.Item(b, "proxy")), listOf(20001, 20002)))

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
        assertEquals("a.example", PingConfig.dialHost(vless, "proxy"))
        // A chain: its first hop (the entry), past a fragment's freedom that has no server.
        val chain = """{"outbounds":[
            {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"exit.example","port":443}]},"streamSettings":{"sockopt":{"dialerProxy":"entry"}}},
            {"tag":"entry","protocol":"shadowsocks","settings":{"servers":[{"address":"192.0.2.10","port":8388}]},"streamSettings":{"sockopt":{"dialerProxy":"frag"}}},
            {"tag":"frag","protocol":"freedom","settings":{"fragment":{"packets":"tlshello"}}}]}"""
        assertEquals("192.0.2.10", PingConfig.dialHost(chain, "proxy"))
        assertEquals("192.0.2.10", PingConfig.dialHost(chain, "entry"))
        // The address in the settings themselves (Hysteria 2; MASQUE as rewritten).
        val flat = """{"outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"address":"b.example","port":443}}]}"""
        assertEquals("b.example", PingConfig.dialHost(flat, "proxy"))
        // None: a protocol client's local SOCKS, a loop without a server, no such outbound.
        val local = """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}}]}"""
        assertNull(PingConfig.dialHost(local, "proxy"))
        val loop = """{"outbounds":[{"tag":"a","protocol":"freedom","streamSettings":{"sockopt":{"dialerProxy":"b"}}},{"tag":"b","protocol":"freedom","streamSettings":{"sockopt":{"dialerProxy":"a"}}}]}"""
        assertNull(PingConfig.dialHost(loop, "a"))
        assertNull(PingConfig.dialHost(vless, "other"))
        assertNull(PingConfig.dialHost("not json", "proxy"))
    }
}
