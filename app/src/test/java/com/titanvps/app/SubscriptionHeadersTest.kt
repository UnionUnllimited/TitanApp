package com.titanvps.app

import com.titanvps.app.data.SubscriptionHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionHeadersTest {

    @Test
    fun parsesRemnawaveHeaders() {
        val headers = mapOf(
            "profile-title" to "base64:VGl0YW5WUFM=",
            "subscription-userinfo" to "upload=100; download=200; total=1073741824; expire=1893456000",
            "support-url" to "https://t.me/support",
            "profile-update-interval" to "6",
            "announce" to "base64:0J/RgNC40LLQtdGC",
        )
        val info = SubscriptionHeaders.parse { headers[it] }
        assertEquals("TitanVPS", info.title)
        assertEquals(300, info.usedBytes)
        assertEquals(1073741824, info.totalBytes)
        assertEquals(1893456000, info.expireAt)
        assertEquals("https://t.me/support", info.supportUrl)
        assertEquals(6, info.updateIntervalHours)
        assertEquals("Привет", info.announce)
    }

    @Test
    fun missingHeadersUseDefaults() {
        val info = SubscriptionHeaders.parse { null }
        assertNull(info.title)
        assertEquals(0, info.totalBytes)
        assertEquals(12, info.updateIntervalHours)
    }

    @Test
    fun rejectsNonHttpSupportUrl() {
        val info = SubscriptionHeaders.parse { if (it == "support-url") "javascript:alert(1)" else null }
        assertNull(info.supportUrl)
    }
}
