package com.titanvps.app

import com.titanvps.app.core.XrayConfigs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigsTest {

    private val remnawaveJson = """
        [
          {
            "remarks": "🇩🇪 Германия",
            "inbounds": [{"tag": "socks", "protocol": "socks", "port": 10808}],
            "outbounds": [
              {"tag": "proxy", "protocol": "vless", "settings": {}},
              {"tag": "direct", "protocol": "freedom"},
              {"tag": "block", "protocol": "blackhole"}
            ],
            "routing": {"rules": [{"type": "field", "domain": ["geosite:private"], "outboundTag": "direct"}]}
          },
          {
            "remarks": "🇳🇱 Нидерланды",
            "outbounds": [{"tag": "direct", "protocol": "freedom"}, {"tag": "nl", "protocol": "vless"}]
          }
        ]
    """.trimIndent()

    @Test
    fun parsesXrayJsonArray() {
        val servers = XrayConfigs.serversFromXrayJson(remnawaveJson)
        assertEquals(2, servers.size)
        assertEquals("🇩🇪 Германия", servers[0].name)
        assertEquals("proxy", servers[0].proxyTag)
        assertEquals("nl", servers[1].proxyTag)
        val cfg = JSONObject(servers[0].xrayJson)
        assertFalse(cfg.has("inbounds"))
        assertFalse(cfg.has("remarks"))
    }

    @Test
    fun wrapsShareLinkOutbounds() {
        val servers = XrayConfigs.serversFromOutbounds(listOf(JSONObject("""{"tag":"Москва","protocol":"vless"}""")))
        assertEquals("Москва", servers[0].name)
        val cfg = JSONObject(servers[0].xrayJson)
        assertEquals("proxy", cfg.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
    }

    @Test
    fun buildRunConfigInjectsTunAndDns() {
        val server = XrayConfigs.serversFromXrayJson(remnawaveJson)[0]
        val cfg = JSONObject(XrayConfigs.buildRunConfig(server.xrayJson, 42, "/data/xray", 1500))

        assertEquals("42", cfg.getJSONObject("env").getString("xray.tun.fd"))
        assertEquals("/data/xray", cfg.getJSONObject("env").getString("xray.location.asset"))

        val inbounds = cfg.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))

        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        assertEquals(XrayConfigs.DNS_OUT_TAG, rules.getJSONObject(0).getString("outboundTag"))
        assertEquals(2, rules.length()) // server rule kept after the DNS hijack
        assertTrue(cfg.has("dns"))

        val outbounds = cfg.getJSONArray("outbounds")
        assertTrue((0 until outbounds.length()).any { outbounds.getJSONObject(it).getString("tag") == XrayConfigs.DNS_OUT_TAG })
    }

    @Test
    fun handlesBomAndUntaggedProxy() {
        val body = "\uFEFF" + """{"remarks":"🇫🇮 Hysteria","outbounds":[{"protocol":"hysteria","settings":{"version":2,"address":"fi.example.com","port":443}},{"tag":"direct","protocol":"freedom"}]}"""
        assertTrue(XrayConfigs.isXrayJson(body))
        val servers = XrayConfigs.serversFromXrayJson(body)
        assertEquals(1, servers.size)
        assertEquals("proxy", servers[0].proxyTag)
        assertEquals("proxy", JSONObject(servers[0].xrayJson).getJSONArray("outbounds").getJSONObject(0).getString("tag"))
    }
}
