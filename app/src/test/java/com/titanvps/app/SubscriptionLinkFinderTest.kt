package com.titanvps.app

import com.titanvps.app.data.DeepLinks
import com.titanvps.app.data.SubscriptionLinkFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionLinkFinderTest {

    private val hosts = setOf("sub-vpn.example.online", "api1.example.su")
    private val finder = SubscriptionLinkFinder(DeepLinks(hosts), hosts)

    @Test
    fun findsPlainLinkInHtml() {
        val html = """<div><input value="https://sub-vpn.example.online/api/sub/c277430c-bd59"><a href="/pay">Оплатить</a></div>"""
        assertEquals("https://sub-vpn.example.online/api/sub/c277430c-bd59", finder.find(html))
    }

    @Test
    fun findsLinkInsideOtherAppDeepLinks() {
        assertEquals(
            "https://sub-vpn.example.online/api/sub/abc",
            finder.find("""<a href="happ://add/https://sub-vpn.example.online/api/sub/abc">Happ</a>"""),
        )
        assertEquals(
            "https://sub-vpn.example.online/api/sub/abc",
            finder.find("v2raytun://import/https%3A%2F%2Fsub-vpn.example.online%2Fapi%2Fsub%2Fabc"),
        )
    }

    @Test
    fun ignoresForeignHostsAndBareDomain() {
        assertNull(finder.find("""<a href="https://evil.example.com/api/sub/abc">x</a>"""))
        assertNull(finder.find("""<a href="https://sub-vpn.example.online/">home</a>"""))
        assertNull(finder.find(""))
    }
}
