package com.titanvps.app

import com.titanvps.app.reminders.Reminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RemindersTest {
    private val moscow = TimeZone.getTimeZone("Europe/Moscow")
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    /** Ends on 13 July 2027 at 00:30 Moscow time. */
    private val endSec = at(moscow, 2027, 7, 13, 0, 30) / 1000

    @Test
    fun bothAhead() {
        val now = at(moscow, 2027, 7, 1, 10)
        assertEquals(
            listOf(
                Reminders.KIND_EXPIRY3 to at(moscow, 2027, 7, 10, 12),
                Reminders.KIND_EXPIRY1 to at(moscow, 2027, 7, 12, 12),
            ),
            Reminders.expiryTimes(endSec, now, moscow),
        )
    }

    @Test
    fun threeDayOnePassed() {
        val now = at(moscow, 2027, 7, 11, 9)
        assertEquals(
            listOf(Reminders.KIND_EXPIRY1 to at(moscow, 2027, 7, 12, 12)),
            Reminders.expiryTimes(endSec, now, moscow),
        )
    }

    @Test
    fun lessThanADayLeftGivesNone() {
        assertEquals(emptyList<Pair<String, Long>>(), Reminders.expiryTimes(endSec, at(moscow, 2027, 7, 12, 13), moscow))
        // No expiry at all.
        assertEquals(emptyList<Pair<String, Long>>(), Reminders.expiryTimes(0, at(moscow, 2027, 7, 1, 10), moscow))
    }

    @Test
    fun noonOfThePhonesZone() {
        val now = at(utc, 2027, 7, 1, 0)
        // 00:30 in Moscow is still 12 July in UTC: a day earlier there, at noon UTC.
        assertEquals(
            listOf(
                Reminders.KIND_EXPIRY3 to at(utc, 2027, 7, 9, 12),
                Reminders.KIND_EXPIRY1 to at(utc, 2027, 7, 11, 12),
            ),
            Reminders.expiryTimes(endSec, now, utc),
        )
        assertEquals(at(moscow, 2027, 7, 12, 12), Reminders.expiryTimes(endSec, now, moscow).last().second)
    }

    @Test
    fun trafficLowBelowTenPercent() {
        // Unlimited (total 0) is never low.
        assertFalse(Reminders.trafficLow(0, 0))
        assertFalse(Reminders.trafficLow(0, 5_000))
        // Exactly 10 % left: not yet.
        assertFalse(Reminders.trafficLow(1_000, 900))
        // 9.9 % left.
        assertTrue(Reminders.trafficLow(1_000, 901))
        assertTrue(Reminders.trafficLow(1_000, 1_000))
        assertTrue(Reminders.trafficLow(1_000, 1_200))
        assertFalse(Reminders.trafficLow(1_000, 0))
    }
}
