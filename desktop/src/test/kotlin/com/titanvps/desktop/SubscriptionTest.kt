package com.titanvps.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The subscription as Windows reads it: «Безлимитные · все серверы», the panel's stub entries,
 * its headers, the core's config built from it, the way off a bypass and the saved state's file.
 */
class SubscriptionTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun row(remarks: String, address: String = "192.0.2.10", port: Int = 443, protocol: String = "vless") = JSONObject()
        .put("remarks", remarks)
        .put("outbounds", JSONArray()
            .put(JSONObject().put("tag", "proxy").put("protocol", protocol)
                .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", address).put("port", port)))))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))

    private fun body(vararg rows: JSONObject) = JSONArray().apply { rows.forEach { put(it) } }.toString()

    private fun shown(vararg rows: JSONObject) = XrayConfigs.serversFromXrayJson(body(*rows)).filterNot(ServerGroups::isHidden)

    // ------------------------------------------------------------ «Безлимитные · все серверы»

    @Test
    fun unlimitedRowIsRenamedAndComesRightAfterAuto() {
        val servers = shown(
            row("🇳🇱 Нидерланды"),
            row("⚡ АВТО | Быстрые обходы"),
            row("⚡ АВТО"),
            row("🇩🇪 Германия"),
            row("🇦🇱 👇БЕЗЛИМИТНЫЕ👇"),
        )
        assertEquals(
            listOf("🇳🇱 Нидерланды", "⚡ АВТО | Быстрые обходы", "⚡ АВТО", ServerGroups.UNLIMITED_NAME, "🇩🇪 Германия"),
            servers.map { it.name },
        )
        val unlimited = servers[3]
        assertEquals(ServerGroups.UNLIMITED_ID, unlimited.id)
        assertTrue(ServerGroups.isUnlimited(unlimited))
        // A regular server (Страны), not a bypass one.
        assertEquals(ServerGroups.Group.SERVERS, ServerGroups.groupOf(unlimited))
    }

    @Test
    fun unlimitedRowWithoutAutoComesFirst() {
        val servers = shown(row("🇳🇱 Нидерланды"), row("👇БЕЗЛИМИТНЫЕ👇"))
        assertEquals(listOf(ServerGroups.UNLIMITED_NAME, "🇳🇱 Нидерланды"), servers.map { it.name })
    }

    @Test
    fun unlimitedRowKeepsItsIdWhereverThePanelPutsIt() {
        val first = shown(row("⚡ АВТО"), row("👇БЕЗЛИМИТНЫЕ👇"), row("🇩🇪 Германия"))
        val later = shown(row("🇩🇪 Германия"), row("🇳🇱 Нидерланды"), row("⚡ АВТО"), row("🇦🇱 👇БЕЗЛИМИТНЫЕ👇"))
        assertEquals(ServerGroups.UNLIMITED_ID, first.single { ServerGroups.isUnlimited(it) }.id)
        assertEquals(ServerGroups.UNLIMITED_ID, later.single { ServerGroups.isUnlimited(it) }.id)
        // As saved and loaded again (state.json keeps the new name): still shown.
        assertFalse(ServerGroups.isHidden(later.single { ServerGroups.isUnlimited(it) }))
    }

    @Test
    fun headersStayHidden() {
        val servers = shown(
            // A header with a dummy address: not the config.
            row("👇БЕЗЛИМИТНЫЕ👇", address = "0.0.0.0", port = 1),
            row("⚡ АВТО"),
            row("ЛИМИТНЫЕ ОСТ: 10 ГБ"),
            row("👇БЕЗЛИМИТНЫЕ👇"),
            // Only the first real one is the row; another stays a header.
            row("👇БЕЗЛИМИТНЫЕ 2👇"),
            row("🇩🇪 Германия"),
        )
        assertEquals(listOf("⚡ АВТО", ServerGroups.UNLIMITED_NAME, "🇩🇪 Германия"), servers.map { it.name })
    }

    @Test
    fun bypassAndNoticeRowsAreNeverTheUnlimitedOne() {
        // Before the real one: neither is taken for it, and both stay hidden.
        val servers = shown(row("Безлимитные обходы"), row("СЕРВЕР ДЛЯ БЕЗЛИМИТНЫХ"), row("⚡ АВТО"), row("👇БЕЗЛИМИТНЫЕ👇"))
        assertEquals(listOf("⚡ АВТО", ServerGroups.UNLIMITED_NAME), servers.map { it.name })
        assertEquals(0, XrayConfigs.serversFromXrayJson(body(row("Безлимитные обходы"))).count { it.id == ServerGroups.UNLIMITED_ID })
    }

    @Test
    fun unlimitedRowWithFlatSettingsAddress() {
        // Hysteria-style outbound: the address in the settings themselves.
        val hy = JSONObject().put("remarks", "👇БЕЗЛИМИТНЫЕ👇").put("outbounds", JSONArray()
            .put(JSONObject().put("tag", "proxy").put("protocol", "hysteria")
                .put("settings", JSONObject().put("address", "192.0.2.20").put("port", 443))))
        assertEquals(listOf(ServerGroups.UNLIMITED_NAME), shown(hy).map { it.name })
    }

    // ------------------------------------------------------------ the panel's stub entries

    private val info = SubscriptionInfo()

    private fun reason(body: String, last: Boolean): String? = try {
        SubscriptionErrors.servers(info, body, last)
        null
    } catch (e: SubscriptionException) {
        e.message
    }

    @Test
    fun stubEntriesWithDummyAddressesTellWhy() {
        // Remnawave's stubs: every row at 0.0.0.0, port 1, the remark is the reason.
        val stubs = body(
            row("Превышен лимит устройств", address = "0.0.0.0", port = 1),
            row("Удалите старое устройство в боте — приложение не поддерживается", address = "0.0.0.0", port = 1),
        )
        assertEquals("Превышен лимит устройств", reason(stubs, last = false))
        assertEquals("Превышен лимит устройств", reason(stubs, last = true))
        val expired = body(row("Подписка истекла", address = "0.0.0.0", port = 1))
        assertEquals("Подписка истекла", reason(expired, last = true))
    }

    @Test
    fun onlyInfoRowsMeanNoServers() {
        val infoRows = body(row("👇БЕЗЛИМИТНЫЕ👇", address = "0.0.0.0", port = 1), row("ЛИМИТНЫЕ ОСТ: 0 ГБ"))
        // Our own UA first: the "Xray" one is tried next.
        assertNull(SubscriptionErrors.servers(info, infoRows, last = false))
        assertEquals("В подписке нет серверов", reason(infoRows, last = true))
    }

    @Test
    fun anUnreadableAnswerGoesOnToTheNextUserAgent() {
        assertNull(SubscriptionErrors.servers(info, "dmxlc3M6Ly9leGFtcGxl", last = false))
        assertEquals("Не удалось разобрать подписку. Напишите в поддержку", reason("dmxlc3M6Ly9leGFtcGxl", last = true))
        assertEquals(
            "Свяжитесь с поддержкой",
            try {
                SubscriptionErrors.servers(SubscriptionInfo(announce = "Свяжитесь с поддержкой"), "", last = true)
                null
            } catch (e: SubscriptionException) {
                e.message
            },
        )
    }

    @Test
    fun aRealListLosesOnlyItsInfoRows() {
        val list = body(row("Обновления подписки в боте", address = "127.0.0.1", port = 1), row("⚡ АВТО"), row("🇩🇪 Германия"))
        assertEquals(listOf("⚡ АВТО", "🇩🇪 Германия"), SubscriptionErrors.servers(info, list, last = false)!!.map { it.name })
    }

    // ------------------------------------------------------------ headers

    @Test
    fun sniffingHeader() {
        fun parse(value: String?) = SubscriptionHeaders.parse { if (it == "sniffing-enable") value else null }.sniffing
        assertEquals(false, parse("0"))
        assertEquals(false, parse(" OFF "))
        assertEquals(false, parse("false"))
        assertEquals(true, parse("1"))
        assertEquals(true, parse("On"))
        assertNull(parse(null))
        assertNull(parse("maybe"))
    }

    @Test
    fun updateIntervalHeader() {
        fun parse(value: String?) = SubscriptionHeaders.parse { if (it == "profile-update-interval") value else null }.updateIntervalHours
        assertEquals(SubscriptionInfo.DEFAULT_UPDATE_HOURS, parse(null))
        assertEquals(SubscriptionInfo.DEFAULT_UPDATE_HOURS, parse("soon"))
        assertEquals(6, parse(" 6 "))
        assertEquals(1, parse("0"))
        assertEquals(168, parse("1000"))
    }

    // ------------------------------------------------------------ the core's config

    private val server: Server = XrayConfigs.serversFromXrayJson(
        JSONObject()
            .put("remarks", "🇩🇪 Германия")
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "socks").put("protocol", "socks")
                .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("tls")).put("routeOnly", true))))
            .put("outbounds", JSONArray()
                .put(JSONObject().put("tag", "proxy").put("protocol", "vless")
                    .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", "192.0.2.30").put("port", 443)))))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .put("routing", JSONObject().put("rules", JSONArray()
                .put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("socks")).put("domain", JSONArray().put("geosite:category-ru")).put("outboundTag", "direct"))))
            .toString(),
    ).single()

    private fun build(sniffing: Boolean? = null, accessLog: Boolean = false, json: String = server.xrayJson) =
        JSONObject(XrayConfigs.buildProxyConfig(json, 10801, 10802, "C:/logs", "proxy", 0, sniffing, accessLog))

    private fun JSONObject.inboundSniffing(i: Int) = getJSONArray("inbounds").getJSONObject(i).getJSONObject("sniffing")

    @Test
    fun sniffingFollowsTheHeader() {
        for (cfg in listOf(build(null), build(true))) {
            for (i in 0..1) {
                val s = cfg.inboundSniffing(i)
                assertTrue(s.getBoolean("enabled"))
                // The panel's own settings are kept.
                assertTrue(s.getBoolean("routeOnly"))
            }
        }
        val off = build(false)
        for (i in 0..1) assertEquals("{\"enabled\":false}", off.inboundSniffing(i).toString())
    }

    @Test
    fun youtubeQuicIsBlockedBeforeEveryOtherRule() {
        val cfg = build()
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        val block = rules.getJSONObject(0)
        assertEquals("udp", block.getString("network"))
        assertEquals("443", block.getString("port"))
        assertTrue("domain:googlevideo.com" in block.getJSONArray("domain").toList())
        // No blackhole in the panel's config: ours is added.
        val outbounds = cfg.getJSONArray("outbounds")
        val added = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.single { it.optString("protocol") == "blackhole" }
        assertEquals(added.getString("tag"), block.getString("outboundTag"))
        // Then the GitHub rule, then the panel's (bound to our inbounds now).
        assertEquals("proxy", rules.getJSONObject(1).getString("outboundTag"))
        assertEquals(listOf("socks-in", "http-in"), rules.getJSONObject(2).getJSONArray("inboundTag").toList())
        assertEquals(3, rules.length())
        // The proxy outbound stays the first (Xray's default).
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"))
    }

    @Test
    fun youtubeQuicGoesToThePanelsBlackhole() {
        val json = JSONObject(server.xrayJson).apply {
            getJSONArray("outbounds").put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        }.toString()
        val cfg = build(json = json)
        assertEquals("block", cfg.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        val outbounds = cfg.getJSONArray("outbounds")
        assertEquals(1, (0 until outbounds.length()).count { outbounds.getJSONObject(it).optString("protocol") == "blackhole" })
    }

    @Test
    fun accessLogOnlyInTechMode() {
        assertEquals("none", build().getJSONObject("log").getString("access"))
        assertEquals("C:/logs/xray-access.log", build(accessLog = true).getJSONObject("log").getString("access"))
        assertEquals("C:/logs/xray-error.log", build().getJSONObject("log").getString("error"))
    }

    // ------------------------------------------------------------ off a bypass server

    private fun named(vararg names: String) = names.mapIndexed { i, n -> Server("s$i", n, "{}", "proxy") }

    @Test
    fun offABypassToTheRegularAuto() {
        val all = named("🇩🇪 Германия", "⚡ АВТО | Быстрые обходы", "Обход 1", "⚡ АВТО", ServerGroups.UNLIMITED_NAME)
        assertEquals("⚡ АВТО", ServerGroups.regularFallback(all) { null }!!.name)
    }

    @Test
    fun offABypassToUnlimitedWithoutAuto() {
        val all = named("🇩🇪 Германия", "⚡ АВТО | Быстрые обходы", ServerGroups.UNLIMITED_NAME)
        assertEquals(ServerGroups.UNLIMITED_NAME, ServerGroups.regularFallback(all) { null }!!.name)
    }

    @Test
    fun offABypassToTheBestPingOtherwise() {
        val all = named("🇩🇪 Германия", "Обход 1", "🇳🇱 Нидерланды", "🇫🇮 Финляндия")
        val pings = mapOf("s0" to 120L, "s1" to 10L, "s2" to 40L, "s3" to -1L)
        assertEquals("🇳🇱 Нидерланды", ServerGroups.regularFallback(all) { pings[it] }!!.name)
        // Nothing measured: the first regular one.
        assertEquals("🇩🇪 Германия", ServerGroups.regularFallback(all) { null }!!.name)
        assertNull(ServerGroups.regularFallback(named("Обход 1", "⚡ АВТО | Быстрые обходы")) { null })
    }

    // ------------------------------------------------------------ the saved state's file

    @Test
    fun aSaveReplacesTheFileWithoutEverRemovingIt() {
        val target = tmp.newFile("state.json")
        AtomicFile.write(target, "one")
        assertEquals("one", target.readText())
        val missing = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.concurrent.atomic.AtomicBoolean()
        val reader = Thread {
            while (!done.get()) if (!target.exists()) missing.incrementAndGet()
        }.apply { start() }
        val lock = Any()
        val writers = (1..4).map { w ->
            Thread { repeat(100) { i -> synchronized(lock) { AtomicFile.write(target, "w$w-$i") } } }.apply { start() }
        }
        writers.forEach { it.join() }
        done.set(true)
        reader.join()
        assertEquals(0, missing.get())
        assertTrue(target.readText().matches(Regex("w[1-4]-99")))
        assertFalse(java.io.File(target.parentFile, "state.json.tmp").exists())
    }

    private fun JSONArray.toList(): List<Any> = (0 until length()).map { get(it) }
}
