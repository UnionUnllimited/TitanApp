package com.titanvps.app

import com.titanvps.app.core.Relay
import com.titanvps.app.data.Server
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RelayTest {
    private fun vless(id: String, name: String, address: String) = Server(
        id, name,
        """{"outbounds": [
             {"tag": "proxy", "protocol": "vless", "settings": {"vnext": [{"address": "$address", "port": 443,
               "users": [{"id": "c277430c-bd59-43a7-9cf6-279a6cad4d27"}]}]}},
             {"tag": "direct", "protocol": "freedom"}]}""",
        "proxy",
    )

    private val sweden = vless("se", "Швеция", "se.example")
    private val germany = vless("de", "Германия", "de.example")
    private val poland = vless("pl", "Польша", "pl.example")
    private val swedenTwin = vless("se2", "Швеция 2", "se.example")
    private val naive = Server(
        "nv", "Германия Naive",
        """{"outbounds": [{"tag": "proxy", "protocol": "shadowsocks", "settings": {"servers": [
             {"address": "de.example", "port": 2096, "password": "x", "method": "chacha20-ietf-poly1305"}]}}]}""",
        "proxy",
    )

    @Test
    fun picksFastestReachableServerOnAnotherAddress() {
        val all = listOf(sweden, germany, poland, swedenTwin, naive)
        val pings = mapOf("se" to -1L, "de" to 80L, "pl" to 40L, "se2" to 10L, "nv" to 5L)
        // Not the blocked address again, not a plugin server: Poland.
        assertEquals("pl", Relay.pick(all, pings, sweden)?.id)
        assertNull(Relay.pick(all, mapOf("se" to -1L, "de" to -1L), sweden))
        assertFalse(Relay.canChain(naive))
    }

    @Test
    fun chainDialsThroughTheRelay() {
        val out = JSONObject(Relay.chain(sweden.xrayJson, "proxy", germany)).getJSONArray("outbounds")
        val proxy = out.getJSONObject(0)
        assertEquals(Relay.TAG, proxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        val relay = (0 until out.length()).map { out.getJSONObject(it) }.first { it.optString("tag") == Relay.TAG }
        assertEquals("de.example", relay.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        // The exit is still Sweden.
        assertEquals("se.example", proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
    }
}
