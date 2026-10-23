package com.titanvps.app

import com.titanvps.app.vpn.BypassPolicy
import com.titanvps.app.vpn.BypassPolicy.Back
import com.titanvps.app.vpn.BypassPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassPolicyTest {

    private val min = 60_000L
    private val start = 10 * BypassPolicy.HOUR_MS

    @Test
    fun whitelistOnlyWhenEverythingSaysSo() {
        assertTrue(BypassPolicy.whitelisted(othersAnswered = false, russianOpens = true, foreignOpens = false))
        // A foreign site opens: the server is the problem, the network is fine.
        assertFalse(BypassPolicy.whitelisted(othersAnswered = false, russianOpens = true, foreignOpens = true))
        // Another regular server answers: just this one is down.
        assertFalse(BypassPolicy.whitelisted(othersAnswered = true, russianOpens = true, foreignOpens = false))
        // Nothing opens at all: no network, a bypass won't help.
        assertFalse(BypassPolicy.whitelisted(othersAnswered = false, russianOpens = false, foreignOpens = false))
    }

    @Test
    fun liftedNeedsTheSiteAndTheServer() {
        assertTrue(BypassPolicy.lifted(foreignOpens = true, serverAnswered = true))
        assertFalse(BypassPolicy.lifted(foreignOpens = true, serverAnswered = false))
        assertFalse(BypassPolicy.lifted(foreignOpens = false, serverAnswered = true))
    }

    @Test
    fun streakNeedsChecksInARow() {
        val misses = BypassPolicy.Streak(BypassPolicy.MISSES)
        assertFalse(misses.add(true))
        // An answer in between starts over.
        assertFalse(misses.add(false))
        assertFalse(misses.add(true))
        assertTrue(misses.add(true))
        misses.reset()
        assertFalse(misses.add(true))
        assertEquals(2, BypassPolicy.MISSES)
        assertEquals(2, BypassPolicy.LIFTED_CHECKS)
    }

    @Test
    fun switchesOnlyWithAutoBypass() {
        val policy = BypassPolicy()
        assertEquals(Decision.Switch, policy.onWhitelist(start, autoBypass = true))
        assertEquals(Decision.Offer, policy.onWhitelist(start, autoBypass = false))
    }

    @Test
    fun waitsThreeMinutesAfterOurOwnReturn() {
        val policy = BypassPolicy()
        policy.recordReturn(start)
        assertEquals(Decision.Wait(3 * min), policy.onWhitelist(start, autoBypass = true))
        assertEquals(Decision.Wait(min), policy.onWhitelist(start + 2 * min, autoBypass = true))
        assertEquals(Decision.Switch, policy.onWhitelist(start + 3 * min, autoBypass = true))
        // Off: only the offer, whenever.
        assertEquals(Decision.Offer, policy.onWhitelist(start + min, autoBypass = false))
    }

    @Test
    fun atMostThreeSwitchesAnHour() {
        val policy = BypassPolicy()
        repeat(3) { i ->
            assertEquals(Decision.Switch, policy.onWhitelist(start + i * 10 * min, autoBypass = true))
            policy.recordSwitch(start + i * 10 * min)
        }
        // The fourth within the hour: only the offer.
        assertEquals(Decision.Offer, policy.onWhitelist(start + 30 * min, autoBypass = true))
        assertEquals(Decision.Offer, policy.onWhitelist(start + 59 * min, autoBypass = true))
        // An hour after the first one, a switch again.
        assertEquals(Decision.Switch, policy.onWhitelist(start + 60 * min, autoBypass = true))
        assertEquals(2, policy.switches(start + 60 * min).size)
    }

    @Test
    fun noFlappingAfterAReturn() {
        // Switch, back, and whitelisted again a minute later: no switch for the rest of the 3 minutes.
        val policy = BypassPolicy()
        policy.recordSwitch(start)
        policy.recordReturn(start + 5 * min)
        assertEquals(Decision.Wait(2 * min), policy.onWhitelist(start + 6 * min, autoBypass = true))
        assertEquals(Decision.Switch, policy.onWhitelist(start + 8 * min, autoBypass = true))
    }

    @Test
    fun savedHistoryIsKept() {
        // As BypassReturn restores it after a revive.
        val policy = BypassPolicy(listOf(start, start + min, start + 2 * min), returnedAt = start + 3 * min)
        assertEquals(Decision.Offer, policy.onWhitelist(start + 4 * min, autoBypass = true))
        assertEquals(start + 3 * min, policy.returnedAt)
        assertEquals(3, policy.switches(start + 4 * min).size)
    }

    @Test
    fun clockSetBackDropsTheHistory() {
        val policy = BypassPolicy(listOf(start, start + min, start + 2 * min), returnedAt = start + 2 * min)
        // Two hours earlier: no cool-down, no switches counted.
        assertEquals(Decision.Switch, policy.onWhitelist(start - 2 * BypassPolicy.HOUR_MS, autoBypass = true))
    }

    @Test
    fun backOnlyWhenWeMovedTheUser() {
        assertEquals(Back.RETURN, BypassPolicy.onLifted(movedByUs = true, autoBypass = true, offered = false))
        // Автопереход off: an offer, once per connection.
        assertEquals(Back.OFFER, BypassPolicy.onLifted(movedByUs = true, autoBypass = false, offered = false))
        assertEquals(Back.NONE, BypassPolicy.onLifted(movedByUs = true, autoBypass = false, offered = true))
        // The user picked the bypass himself: never a switch, one offer.
        assertEquals(Back.OFFER, BypassPolicy.onLifted(movedByUs = false, autoBypass = true, offered = false))
        assertEquals(Back.NONE, BypassPolicy.onLifted(movedByUs = false, autoBypass = true, offered = true))
    }
}
