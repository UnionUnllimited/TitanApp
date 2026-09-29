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
        assertEquals("UseIPv4", cfg.getJSONObject("dns").getString("queryStrategy"))

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

    @Test
    fun rulesBoundToOldInboundsFollowTun() {
        val body = """{"remarks":"RU direct","inbounds":[{"tag":"socks","protocol":"socks"},{"tag":"http","protocol":"http"}],
            "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
            "routing":{"rules":[
              {"type":"field","inboundTag":["socks","http"],"domain":["geosite:category-ru"],"outboundTag":"direct"},
              {"type":"field","inboundTag":["api"],"outboundTag":"api"},
              {"type":"field","ip":["geoip:ru"],"outboundTag":"direct"}]}}"""
        val cfg = JSONObject(XrayConfigs.serversFromXrayJson(body)[0].xrayJson)
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        assertEquals(XrayConfigs.TUN_TAG, rules.getJSONObject(0).getJSONArray("inboundTag").getString(0))
        assertEquals(1, rules.getJSONObject(0).getJSONArray("inboundTag").length())
        assertEquals("api", rules.getJSONObject(1).getJSONArray("inboundTag").getString(0))
        assertFalse(rules.getJSONObject(2).has("inboundTag"))
    }

    @Test
    fun pingTagsFollowCatchAllBalancer() {
        val json = """{"outbounds":[{"tag":"bal","protocol":"vless"},{"tag":"bal-2","protocol":"vless"},
            {"tag":"bal-3","protocol":"hysteria"},{"tag":"yt-ru1","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
            "routing":{"balancers":[{"tag":"BAL-LTE","selector":["bal"]},{"tag":"yt_balancer","selector":["yt-ru"]}],
            "rules":[{"domain":["domain:youtube.com"],"balancerTag":"yt_balancer"},
                     {"domain":["regexp:\\.ru$"],"outboundTag":"direct"},
                     {"network":"tcp,udp","balancerTag":"BAL-LTE"}]}}"""
        assertEquals(listOf("bal", "bal-2", "bal-3"), XrayConfigs.pingTags(json, "bal"))
        assertEquals(listOf("bal", "bal-2"), XrayConfigs.pingTags(json, "bal", max = 2))
        assertEquals(listOf("proxy"), XrayConfigs.pingTags("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""", "proxy"))
    }

    @Test
    fun tunUsesPanelSniffing() {
        val body = """{"inbounds":[{"tag":"socks","protocol":"socks","sniffing":{"enabled":true,"destOverride":["http","tls","quic"],"routeOnly":false}}],
            "outbounds":[{"tag":"proxy","protocol":"vless"}]}"""
        val server = XrayConfigs.serversFromXrayJson(body)[0]
        val run = JSONObject(XrayConfigs.buildRunConfig(server.xrayJson, 3, "/a", 1500))
        val sniff = run.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing")
        assertFalse(sniff.getBoolean("routeOnly"))
        assertFalse(run.has("_titanSniffing"))
        // Default without panel sniffing: routeOnly=false as well.
        val plain = XrayConfigs.serversFromXrayJson("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")[0]
        val run2 = JSONObject(XrayConfigs.buildRunConfig(plain.xrayJson, 3, "/a", 1500))
        assertFalse(run2.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing").getBoolean("routeOnly"))
    }

    @Test
    fun sameNodeUnderDifferentTagsHasSameKey() {
        val country = """{"outbounds":[{"tag":"bal-3","protocol":"vless","settings":{"address":"de1.example.com","port":443}}]}"""
        val auto = """{"outbounds":[{"tag":"bal-17","protocol":"vless","settings":{"address":"de1.example.com","port":443}},
            {"tag":"bal-18","protocol":"vless","settings":{"address":"fi1.example.com","port":443}}]}"""
        assertEquals(XrayConfigs.endpointKey(country, "bal-3"), XrayConfigs.endpointKey(auto, "bal-17"))
        assertTrue(XrayConfigs.endpointKey(auto, "bal-17") != XrayConfigs.endpointKey(auto, "bal-18"))
    }
}
