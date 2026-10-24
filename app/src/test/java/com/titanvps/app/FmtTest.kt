package com.titanvps.app

import com.titanvps.app.data.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {

    @Test
    fun speed() {
        assertEquals("500 Б/с", Fmt.speed(500.0))
        assertEquals("2 КБ/с", Fmt.speed(1536.0))
        assertEquals("3.0 МБ/с", Fmt.speed(3_145_728.0))
        assertEquals("0 Б/с", Fmt.speed(-5.0))
    }

    @Test
    fun bytes() {
        assertEquals("300 МБ", Fmt.bytes(300L * 1024 * 1024))
        assertEquals("1.5 ГБ", Fmt.bytes(1536L * 1024 * 1024))
        assertEquals("0 МБ", Fmt.bytes(0))
    }

    @Test
    fun devicesWord() {
        assertEquals("устройство", Fmt.devicesWord(1))
        assertEquals("устройства", Fmt.devicesWord(2))
        assertEquals("устройств", Fmt.devicesWord(5))
        assertEquals("устройств", Fmt.devicesWord(11))
        assertEquals("устройство", Fmt.devicesWord(21))
        assertEquals("устройства", Fmt.devicesWord(22))
        assertEquals("устройств", Fmt.devicesWord(111))
    }

    @Test
    fun serversWord() {
        assertEquals("сервер", Fmt.serversWord(1))
        assertEquals("сервера", Fmt.serversWord(3))
        assertEquals("серверов", Fmt.serversWord(12))
        assertEquals("сервер", Fmt.serversWord(21))
        assertEquals("серверов", Fmt.serversWord(29))
    }

    @Test
    fun ago() {
        val now = 1_800_000_000_000L
        val min = 60_000L
        val hour = 60 * min
        val day = 24 * hour
        assertEquals("только что", Fmt.ago(now - 30_000, now))
        assertEquals("1 минуту назад", Fmt.ago(now - min, now))
        assertEquals("5 минут назад", Fmt.ago(now - 5 * min, now))
        assertEquals("1 час назад", Fmt.ago(now - hour, now))
        assertEquals("2 часа назад", Fmt.ago(now - 2 * hour, now))
        assertEquals("5 часов назад", Fmt.ago(now - 5 * hour, now))
        assertEquals("1 день назад", Fmt.ago(now - day, now))
        assertEquals("3 дня назад", Fmt.ago(now - 3 * day, now))
        assertEquals("только что", Fmt.ago(now + min, now))
    }

    @Test
    fun timerAndDays() {
        assertEquals("01:01:01", Fmt.timer(3_661_000))
        assertEquals("00:00:00", Fmt.timer(-1))
        assertEquals(3L, Fmt.daysLeft(1_000 + 3 * 86_400 + 5, 1_000))
        assertEquals(0L, Fmt.daysLeft(1_000, 2_000))
    }

    @Test
    fun price() {
        assertEquals("199 ₽", Fmt.price(199.0))
        assertEquals("199.50 ₽", Fmt.price(199.5))
    }

    @Test
    fun dates() {
        assertEquals("Бессрочно", Fmt.date(0))
        assertEquals("Финляндия", Fmt.countryName("fi"))
        assertEquals("Германия", Fmt.countryName("DE"))
        assertEquals("Англия", Fmt.countryName("gb-eng"))
    }
}
