package com.titanvps.app.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * When a sideways move is a swipe to the next tab (Главная ↔ Подписка ↔ Профиль), and where
 * the pages go when it's let go. Plain numbers, so it is tested on its own.
 */
internal object TabSwipe {
    enum class Decision { UNDECIDED, HORIZONTAL, VERTICAL }

    /**
     * Sideways this far before the pages follow: past where a server row's swipe (20 dp) and
     * Выкл | Вкл (16 dp) decide, so they always decide first.
     */
    val CLAIM_AT = 24.dp

    /** A flick this fast (per second) goes to the next tab however short it was. */
    val FLING_AT = 400.dp

    /** Sideways at least this many times more than up or down (as a row's swipe). */
    private const val DOMINANCE = 2f

    /**
     * [dx], [dy]: how far the finger went since it touched. Up or down past the touch slop is
     * the list's (or nobody's) for good; only a clearly sideways move [claimAt] long is a swipe.
     */
    fun decide(dx: Float, dy: Float, slop: Float, claimAt: Float): Decision = when {
        abs(dy) >= slop && abs(dx) <= abs(dy) * DOMINANCE -> Decision.VERTICAL
        abs(dx) >= claimAt && abs(dx) > abs(dy) * DOMINANCE -> Decision.HORIZONTAL
        else -> Decision.UNDECIDED
    }

    /**
     * The page a swipe from [start] ends on, one step at most: [moved] is how far the pages went
     * (in pages, forward = toward Профиль), [velocity] the finger's speed on release (px/s,
     * forward = toward Профиль). A flick goes its way, anything else to the nearer page.
     */
    fun target(start: Int, count: Int, moved: Float, velocity: Float, flingAt: Float): Int {
        val position = start + moved
        val page = when {
            velocity >= flingAt -> ceil(position).toInt()
            velocity <= -flingAt -> floor(position).toInt()
            else -> position.roundToInt()
        }
        return page.coerceIn(start - 1, start + 1).coerceIn(0, (count - 1).coerceAtLeast(0))
    }
}

/** The pager's own nested scrolling stays out of the pages' lists and pull to refresh. */
internal val NoPagerNestedScroll = object : NestedScrollConnection {}

/**
 * Swipes between the tabs on [pager] (its own dragging is off). The pages' gestures go first:
 * a move anything in them takes (a server row's swipe, Выкл | Вкл, a list scrolling) is
 * theirs for the whole touch, so is an up or down one; the pages follow only a clearly sideways
 * one nobody took, from [TabSwipe.CLAIM_AT]. [onSwiped]: it went to another tab.
 */
internal fun Modifier.tabSwipe(pager: PagerState, onSwiped: () -> Unit): Modifier = composed {
    val swiped by rememberUpdatedState(onSwiped)
    pointerInput(pager) {
        val slop = viewConfiguration.touchSlop
        val claimAt = TabSwipe.CLAIM_AT.toPx()
        val flingAt = TabSwipe.FLING_AT.toPx()
        coroutineScope {
            awaitEachGesture {
                // The pages' buttons take the touch down; only the moves after it are looked at.
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = pager.currentPage
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var decision = TabSwipe.Decision.UNDECIDED
                var deltas: Channel<Float>? = null
                var drag: Job? = null
                var released = false
                try {
                    while (true) {
                        // The main pass comes here after the pages: what they took is consumed by now.
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (decision == TabSwipe.Decision.UNDECIDED) {
                            // A tap, a second finger, or a move something under the finger took.
                            if (!change.pressed || change.isConsumed || event.changes.size > 1) break
                            val d = change.position - down.position
                            decision = TabSwipe.decide(d.x, d.y, slop, claimAt)
                            if (decision == TabSwipe.Decision.VERTICAL) break
                            if (decision == TabSwipe.Decision.UNDECIDED) continue
                            // Ours from here: a drag of the pages that stops any settling still going.
                            val channel = Channel<Float>(Channel.UNLIMITED)
                            deltas = channel
                            drag = launch {
                                pager.scroll(MutatePriority.UserInput) { for (delta in channel) scrollBy(delta) }
                            }
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            // Let go; consumed already: the touch was cancelled (no flick then).
                            released = !change.isConsumed
                            change.consume()
                            break
                        }
                        val dx = change.positionChange().x
                        // Taken: the buttons under the finger let go of their press.
                        change.consume()
                        if (dx != 0f) deltas?.trySend(-dx)
                    }
                } finally {
                    deltas?.close()
                    if (decision == TabSwipe.Decision.HORIZONTAL) {
                        val velocity = if (released) tracker.calculateVelocity().x else 0f
                        launch {
                            drag?.join()
                            val moved = pager.currentPage + pager.currentPageOffsetFraction - start
                            val target = TabSwipe.target(start, pager.pageCount, moved, -velocity, flingAt)
                            if (target != start) swiped()
                            pager.animateScrollToPage(target)
                        }
                    }
                }
            }
        }
    }
}
