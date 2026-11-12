package com.titanvps.app

import com.titanvps.app.ui.home.ScrollTop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollTopTest {

    @Test
    fun scrolledIsExactAtTheTop() {
        assertEquals(0f, ScrollTop.scrolled(0, 0, 70f), 0f)
        assertEquals(30f, ScrollTop.scrolled(0, 30, 70f), 0f)
        assertEquals(730f, ScrollTop.scrolled(10, 30, 70f), 0f)
    }

    @Test
    fun showsAboutAScreenDown() {
        assertFalse(ScrollTop.visible(shown = false, scrolled = 0f, viewport = 2000))
        assertFalse(ScrollTop.visible(shown = false, scrolled = 1999f, viewport = 2000))
        assertTrue(ScrollTop.visible(shown = false, scrolled = 2000f, viewport = 2000))
    }

    @Test
    fun hidesAboveHalfAScreenNotFlickeringInBetween() {
        assertTrue(ScrollTop.visible(shown = true, scrolled = 1500f, viewport = 2000))
        assertTrue(ScrollTop.visible(shown = true, scrolled = 1001f, viewport = 2000))
        assertFalse(ScrollTop.visible(shown = true, scrolled = 1000f, viewport = 2000))
        assertFalse(ScrollTop.visible(shown = true, scrolled = 0f, viewport = 2000))
        assertFalse(ScrollTop.visible(shown = false, scrolled = 1500f, viewport = 2000))
    }

    @Test
    fun notBeforeTheListIsLaidOut() {
        assertFalse(ScrollTop.visible(shown = false, scrolled = 500f, viewport = 0))
        assertFalse(ScrollTop.visible(shown = true, scrolled = 500f, viewport = 0))
    }
}
