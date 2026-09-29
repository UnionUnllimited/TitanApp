package com.titanvps.app

import com.titanvps.app.core.PingConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
