package com.titanvps.app

import com.titanvps.app.data.SubscriptionErrors
import com.titanvps.app.data.SubscriptionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionErrorsTest {
    @Test
    fun stubEntriesBecomeTheMessage() {
        assertTrue(SubscriptionErrors.stub(listOf("Приложение не поддерживается"))!!.startsWith("Приложение не поддерживается"))
        assertEquals("Вы были заблокированы", SubscriptionErrors.stub(listOf("Вы были заблокированы")))
        // A real server among them: not a stub answer.
        assertNull(SubscriptionErrors.stub(listOf("Вы были заблокированы", "🇩🇪 Германия")))
        assertNull(SubscriptionErrors.stub(emptyList()))
    }

    @Test
    fun announceOrEmptyAnswer() {
        assertEquals("Подписка истекла", SubscriptionErrors.unusable(SubscriptionInfo(announce = "Подписка истекла"), "<html>"))
        assertTrue("лимит устройств" in SubscriptionErrors.unusable(SubscriptionInfo(), "  "))
    }
}
