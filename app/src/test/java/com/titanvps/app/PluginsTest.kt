package com.titanvps.app

import com.titanvps.app.core.Plugins
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginsTest {
    private val json = """
        {"outbounds": [
          {"tag": "proxy", "protocol": "shadowsocks", "settings": {"servers": [
            {"address": "node.example", "port": 2096, "password": "Pw+1/x=", "method": "chacha20-ietf-poly1305"}]}},
          {"tag": "direct", "protocol": "freedom"}]}
    """

    @Test
    fun endpointFromShadowsocksPlaceholder() {
        val ep = Plugins.endpoint("🇱🇻 Латвия Naive", json, "proxy")!!
        assertEquals(Plugins.Kind.NAIVE, ep.kind)
        assertEquals("node.example", ep.host)
        assertEquals(2096, ep.port)
        // Must match server/titan-node-sync.py derive("Pw+1/x=").
        assertEquals("53d28cea4f8aaf1e", ep.user)
        assertEquals("aefdd92ef0207ee32d45b8b17e553aef", ep.password)
        assertEquals(Plugins.Kind.MIERU, Plugins.endpoint("Латвия MIERU", json, "proxy")!!.kind)
        // Must match server/titan-node-sync.py tuic_uuid("Pw+1/x=").
        val tuic = Plugins.endpoint("🇱🇻 Латвия TUIC", json, "proxy")!!
        assertEquals(Plugins.Kind.TUIC, tuic.kind)
        assertEquals("2d45b8b1-7e55-3aef-357e-e9286dc51f35", tuic.tuicUuid)
        assertEquals(Plugins.Kind.MASQUE, Plugins.endpoint("Латвия MASQUE TCP", json, "proxy")!!.kind)
        // Must match server/titan-node-sync.py: SS2022_SERVER_KEY + ":" + ss2022_key("titan-ss2022:" + secret).
        val stls = Plugins.endpoint("🇱🇻 Латвия ShadowTLS", json, "proxy")!!
        assertEquals(Plugins.Kind.SHADOWTLS, stls.kind)
        assertEquals("QIkOC+7qEbZ9NjHwFOeGOg==:f/MV3BuEkMAGhNsqz2TzIQ==", stls.ss2022Password)
        assertEquals(Plugins.Kind.ANYTLS, Plugins.endpoint("Латвия AnyTLS", json, "proxy")!!.kind)
        assertEquals(Plugins.Kind.SSH, Plugins.endpoint("Латвия SSH", json, "proxy")!!.kind)
        // Host Mapper: protocol from the inbound tag Remnawave copies into the outbound.
        fun mapped(value: String) = json.replace("\"tag\": \"proxy\",", "\"tag\": \"proxy\", \"titan\": \"$value\",")
        assertEquals(Plugins.Kind.TUIC, Plugins.endpoint("🇱🇻 Латвия 2", mapped("titan-tuic-lv"), "proxy")!!.kind)
        assertEquals(Plugins.Kind.NAIVE, Plugins.endpoint("Латвия TUIC", mapped("titan-naive-lv"), "proxy")!!.kind)
        val masque = Plugins.endpoint("Латвия 3", mapped("masque-tcp"), "proxy")!!
        assertEquals(Plugins.Kind.MASQUE, masque.kind)
        assert(masque.http2)
        assert(!Plugins.endpoint("Латвия 4", mapped("titan-masque-lv"), "proxy")!!.http2)
        assertNull(Plugins.endpoint("Латвия WiFi LTE", json, "proxy"))
    }

    @Test
    fun proxyOutboundGoesToLocalSocks() {
        val out = JSONObject(Plugins.withLocalSocks(json, "proxy", 12345)).getJSONArray("outbounds")
        val proxy = out.getJSONObject(0)
        assertEquals("proxy", proxy.getString("tag"))
        assertEquals("socks", proxy.getString("protocol"))
        assertEquals(12345, proxy.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getInt("port"))
        assertEquals("direct", out.getJSONObject(1).getString("tag"))
    }
}
