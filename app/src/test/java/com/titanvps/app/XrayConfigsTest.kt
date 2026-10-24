package com.titanvps.app

import com.titanvps.app.core.XrayConfigs
import com.titanvps.app.data.ServerGroups
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
        // DNS hijack, YouTube QUIC block, then the server's own rule.
        assertEquals(3, rules.length())
        val quic = rules.getJSONObject(1)
        assertEquals("udp", quic.getString("network"))
        assertEquals("443", quic.getString("port"))
        assertTrue(quic.getJSONArray("domain").toString().contains("googlevideo.com"))
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

    /**
     * Shaped like the panel's «👇БЕЗЛИМИТНЫЕ👇» row, all values made up: DNS tagged dns-in,
     * BAL-LTE over "bal", YouTube over "yt-ru", and a catch-all with a "network" key.
     */
    private fun unlimitedConfig(remarks: String) = """
        {
          "remarks": "$remarks",
          "dns": {"tag": "dns-in", "servers": ["https://doh.example.org/dns-query"]},
          "inbounds": [{"tag": "socks", "protocol": "socks", "port": 10808}],
          "outbounds": [
            {"tag": "bal-de-1", "protocol": "vless", "settings": {"vnext": [{"address": "de1.example.net", "port": 443,
              "users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "mlkem768x25519plus.native.0rtt.fake"}]}]},
              "streamSettings": {"network": "xhttp"}},
            {"tag": "bal-fi-1", "protocol": "vless", "settings": {"vnext": [{"address": "fi1.example.net", "port": 443,
              "users": [{"id": "22222222-2222-2222-2222-222222222222"}]}]}, "streamSettings": {"network": "grpc"}},
            {"tag": "bal-nl-hy", "protocol": "hysteria", "settings": {"version": 2, "address": "nl1.example.net", "port": 443}},
            {"tag": "yt-ru-1", "protocol": "vless", "settings": {"vnext": [{"address": "yt1.example.net", "port": 443,
              "users": [{"id": "33333333-3333-3333-3333-333333333333"}]}]}},
            {"tag": "direct", "protocol": "freedom"},
            {"tag": "block", "protocol": "blackhole"}
          ],
          "burstObservatory": {"subjectSelector": ["bal", "yt-ru"]},
          "routing": {
            "balancers": [
              {"tag": "BAL-LTE", "selector": ["bal"], "strategy": {"type": "leastLoad"}},
              {"tag": "yt_balancer", "selector": ["yt-ru", "bal"], "fallbackTag": "bal", "strategy": {"type": "leastLoad"}}
            ],
            "rules": [
              {"type": "field", "inboundTag": ["dns-in"], "balancerTag": "BAL-LTE"},
              {"type": "field", "protocol": ["bittorrent"], "outboundTag": "direct"},
              {"type": "field", "domain": ["domain:youtube.com"], "balancerTag": "yt_balancer"},
              {"type": "field", "ip": ["geoip:private"], "outboundTag": "direct"},
              {"type": "field", "domain": ["regexp:\\.ru$"], "outboundTag": "direct"},
              {"type": "field", "network": "udp", "port": "443", "outboundTag": "block"},
              {"type": "field", "balancerTag": "BAL-LTE", "network": "tcp,udp"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun unlimitedRowIsRenamedAndFirstOnParse() {
        val country = """{"remarks":"🇩🇪 Германия","outbounds":[{"tag":"proxy","protocol":"vless",
            "settings":{"vnext":[{"address":"de1.example.net","port":443,"users":[{"id":"0"}]}]}}]}"""
        val servers = XrayConfigs.serversFromXrayJson("[$country,${unlimitedConfig("🇦🇱 👇БЕЗЛИМИТНЫЕ👇")}]")
        assertEquals(listOf(ServerGroups.UNLIMITED_NAME, "🇩🇪 Германия"), servers.map { it.name })
        val unlimited = servers[0]
        assertEquals(XrayConfigs.UNLIMITED_ID, unlimited.id)
        assertEquals("bal-de-1", unlimited.proxyTag)
        assertFalse(ServerGroups.isHidden(unlimited))
        // Other rows keep their ids.
        assertEquals("json-0-${"🇩🇪 Германия".hashCode()}", servers[1].id)
        // The same id after a refresh, wherever the panel puts it and whatever emoji round the word.
        val again = XrayConfigs.serversFromXrayJson("[${unlimitedConfig("👇 Безлимитные 👇")},$country]")
        assertEquals(listOf(XrayConfigs.UNLIMITED_ID, servers[1].id.replace("json-0", "json-1")), again.map { it.id })
        // A second such row stays a hidden header.
        val twice = XrayConfigs.serversFromXrayJson("[${unlimitedConfig("👇БЕЗЛИМИТНЫЕ👇")},${unlimitedConfig("👇БЕЗЛИМИТНЫЕ 2👇")}]")
        assertEquals(listOf(ServerGroups.UNLIMITED_NAME, "👇БЕЗЛИМИТНЫЕ 2👇"), twice.map { it.name })
        assertTrue(ServerGroups.isHidden(twice[1]))
        // The DNS rule keeps its tag: dns-in is the DNS module's, not an inbound.
        val rules = JSONObject(unlimited.xrayJson).getJSONObject("routing").getJSONArray("rules")
        assertEquals("dns-in", rules.getJSONObject(0).getJSONArray("inboundTag").getString(0))
    }

    @Test
    fun unlimitedRowIsPingedOnItsCatchAllBalancer() {
        val unlimited = XrayConfigs.serversFromXrayJson(unlimitedConfig("🇦🇱 👇БЕЗЛИМИТНЫЕ👇"))[0]
        // BAL-LTE's members only (not yt-ru), as for АВТО, and under the same cap.
        assertEquals(listOf("bal-de-1", "bal-fi-1", "bal-nl-hy"), XrayConfigs.pingTags(unlimited.xrayJson, unlimited.proxyTag))
        assertEquals(listOf("bal-de-1", "bal-fi-1"), XrayConfigs.pingTags(unlimited.xrayJson, unlimited.proxyTag, max = 2))
    }

    @Test
    fun dummyUnlimitedHeaderKeepsItsName() {
        val body = """{"remarks":"🇦🇱 👇БЕЗЛИМИТНЫЕ👇","outbounds":[{"tag":"proxy","protocol":"vless",
            "settings":{"vnext":[{"address":"127.0.0.1","port":1,"users":[{"id":"0"}]}]}}]}"""
        val server = XrayConfigs.serversFromXrayJson(body)[0]
        assertEquals("🇦🇱 👇БЕЗЛИМИТНЫЕ👇", server.name)
        assertTrue(ServerGroups.isHidden(server))
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

    private val panelSniffingJson = """{"inbounds":[{"tag":"socks","protocol":"socks","sniffing":{"enabled":false,
        "destOverride":["http","tls"],"routeOnly":true,"domainsExcluded":["courier.push.apple.com"]}}],
        "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
        "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"type":"field","domain":["geosite:category-ru"],"outboundTag":"direct"}]}}"""

    @Test
    fun noSniffingHeaderKeepsTheConfig() {
        for (body in listOf(panelSniffingJson, """{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")) {
            val server = XrayConfigs.serversFromXrayJson(body)[0]
            val before = XrayConfigs.buildRunConfig(server.xrayJson, 3, "/a", 1500, "/logs", server.proxyTag)
            assertEquals(before, XrayConfigs.buildRunConfig(server.xrayJson, 3, "/a", 1500, "/logs", server.proxyTag, sniffingEnable = null))
            // «sniffing-enable: 1» is what Titan always did.
            assertEquals(before, XrayConfigs.buildRunConfig(server.xrayJson, 3, "/a", 1500, "/logs", server.proxyTag, sniffingEnable = true))
        }
        // The panel's settings, turned on.
        val server = XrayConfigs.serversFromXrayJson(panelSniffingJson)[0]
        val sniff = JSONObject(XrayConfigs.buildRunConfig(server.xrayJson, 3, "/a", 1500))
            .getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing")
        assertTrue(sniff.getBoolean("enabled"))
        assertTrue(sniff.getBoolean("routeOnly"))
        assertEquals("courier.push.apple.com", sniff.getJSONArray("domainsExcluded").getString(0))
    }

    @Test
    fun sniffingHeaderOffTurnsTunSniffingOff() {
        for (body in listOf(panelSniffingJson, """{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")) {
            val server = XrayConfigs.serversFromXrayJson(body)[0]
            val on = JSONObject(XrayConfigs.buildRunConfig(server.xrayJson, 3, "/a", 1500, "/logs", server.proxyTag))
            val off = JSONObject(XrayConfigs.buildRunConfig(server.xrayJson, 3, "/a", 1500, "/logs", server.proxyTag, sniffingEnable = false))
            val tun = off.getJSONArray("inbounds").getJSONObject(0)
            assertEquals(XrayConfigs.TUN_TAG, tun.getString("tag"))
            assertEquals("""{"enabled":false}""", tun.getJSONObject("sniffing").toString())
            assertFalse(off.has("_titanSniffing"))
            // Everything else as with sniffing on.
            on.getJSONArray("inbounds").getJSONObject(0).put("sniffing", JSONObject().put("enabled", false))
            assertEquals(on.toString(), off.toString())
        }
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
