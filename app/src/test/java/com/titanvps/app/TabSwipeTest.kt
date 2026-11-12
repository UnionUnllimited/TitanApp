package com.titanvps.app

import com.titanvps.app.ui.TabSwipe
import com.titanvps.app.ui.TabSwipe.Decision
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwipeTest {

    // Pixels at density 1: the touch slop 8, the claim 24, a flick 400 a second.
    private val slop = 8f
    private val claim = 24f
    private val fling = 400f

    private fun decide(dx: Float, dy: Float) = TabSwipe.decide(dx, dy, slop, claim)

    @Test
    fun aClearlySidewaysMoveIsASwipeOnlyFromTheClaim() {
        assertEquals(Decision.UNDECIDED, decide(10f, 0f))
        assertEquals(Decision.UNDECIDED, decide(-23f, 2f))
        assertEquals(Decision.HORIZONTAL, decide(24f, 0f))
        assertEquals(Decision.HORIZONTAL, decide(-30f, 7f))
    }

    @Test
    fun upOrDownPastTheSlopIsNeverASwipe() {
        assertEquals(Decision.VERTICAL, decide(0f, 8f))
        assertEquals(Decision.VERTICAL, decide(16f, -8f))
        assertEquals(Decision.VERTICAL, decide(40f, 20f))
        assertEquals(Decision.VERTICAL, decide(30f, 15f))
        // Diagonal under the slop: not yet anything.
        assertEquals(Decision.UNDECIDED, decide(14f, 7f))
    }

    @Test
    fun aServerRowAndTheSliderDecideFirst() {
        // SwipeRow and PowerSlider, with the same touch slop: up or down at once, else sideways
        // from 20 dp (twice as much sideways) and 16 dp (1.5 times).
        fun rowVertical(x: Float, y: Float) = abs(y) >= slop && abs(x) <= abs(y) * 2
        fun rowTakes(x: Float, y: Float) = !rowVertical(x, y) && hypot(x, y) >= 20f && abs(x) > abs(y) * 2
        fun sliderVertical(x: Float, y: Float) = abs(y) >= slop && abs(x) <= abs(y) * 1.5f
        fun sliderTakes(x: Float, y: Float) = !sliderVertical(x, y) && hypot(x, y) >= 16f && abs(x) > abs(y) * 1.5f
        var y = -40f
        while (y <= 40f) {
            var x = -80f
            while (x <= 80f) {
                val tabs = decide(x, y)
                // Where the tabs would take the move, the row and the slider took it already.
                if (tabs == Decision.HORIZONTAL) {
                    assertTrue("row at $x,$y", rowTakes(x, y))
                    assertTrue("slider at $x,$y", sliderTakes(x, y))
                }
                // Where they gave it to the list, so do the tabs.
                if (rowVertical(x, y) || sliderVertical(x, y)) assertEquals("at $x,$y", Decision.VERTICAL, tabs)
                x += 0.25f
            }
            y += 0.25f
        }
    }

    @Test
    fun letGoItSettlesOnTheNearerTab() {
        assertEquals(0, TabSwipe.target(0, 3, moved = 0.3f, velocity = 0f, flingAt = fling))
        assertEquals(1, TabSwipe.target(0, 3, moved = 0.6f, velocity = 0f, flingAt = fling))
        assertEquals(1, TabSwipe.target(2, 3, moved = -0.7f, velocity = 100f, flingAt = fling))
    }

    @Test
    fun aFlickGoesItsWayOneTabAtMost() {
        assertEquals(1, TabSwipe.target(0, 3, moved = 0.1f, velocity = 500f, flingAt = fling))
        assertEquals(0, TabSwipe.target(1, 3, moved = -0.1f, velocity = -500f, flingAt = fling))
        // Thrown back the other way: stays.
        assertEquals(1, TabSwipe.target(1, 3, moved = 0.4f, velocity = -900f, flingAt = fling))
        // Far and fast: still the next one only.
        assertEquals(1, TabSwipe.target(0, 3, moved = 1.4f, velocity = 5000f, flingAt = fling))
    }

    @Test
    fun theEndsStayPut() {
        assertEquals(0, TabSwipe.target(0, 3, moved = 0f, velocity = -900f, flingAt = fling))
        assertEquals(2, TabSwipe.target(2, 3, moved = 0f, velocity = 900f, flingAt = fling))
        assertEquals(0, TabSwipe.target(0, 3, moved = 0f, velocity = 900f, flingAt = fling))
    }
}
