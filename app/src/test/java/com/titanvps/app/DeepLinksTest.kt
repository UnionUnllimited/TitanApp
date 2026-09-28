package com.titanvps.app

import com.titanvps.app.data.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {

    private val links = DeepLinks(setOf("sub.titan.test"))

    @Test
    fun customSchemeWithAppendedUrl() {
        assertEquals(
            "https://sub.titan.test/AbCd123",
            links.extractSubscriptionUrl("titanvps://import/https://sub.titan.test/AbCd123"),
        )
    }

    @Test
    fun customSchemeWithEncodedUrl() {
        assertEquals(
            "https://sub.titan.test/AbCd123",
            links.extractSubscriptionUrl("titanvps://import?url=https%3A%2F%2Fsub.titan.test%2FAbCd123"),
        )
        assertEquals(
            "https://sub.titan.test/AbCd123",
            links.extractSubscriptionUrl("titanvps://add/https%3A%2F%2Fsub.titan.test%2FAbCd123"),
        )
    }

    @Test
    fun appLink() {
        assertEquals("https://sub.titan.test/x1", links.extractSubscriptionUrl("https://sub.titan.test/x1"))
    }

    @Test
    fun rejectsForeignHosts() {
        assertNull(links.extractSubscriptionUrl("titanvps://import/https://evil.test/abc"))
        assertNull(links.extractSubscriptionUrl("https://sub.titan.test.evil.test/abc"))
        assertNull(links.extractSubscriptionUrl("https://evil.test@sub.titan.test/abc"))
        assertNull(links.extractSubscriptionUrl("http://sub.titan.test/abc"))
        assertNull(links.extractSubscriptionUrl("https://sub.titan.test/"))
        assertNull(links.extractSubscriptionUrl("titanvps://other/https://sub.titan.test/abc"))
    }
}
