package com.titanvps.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/** Numbers, sizes and dates as the screens show them (Russian; a dot in decimals, as on iOS). */
object Fmt {
    private val RU: Locale = Locale.forLanguageTag("ru")

    /** "2.4 МБ/с", "512 КБ/с", "300 Б/с". */
    fun speed(bytesPerSec: Double): String {
        val b = if (bytesPerSec.isNaN()) 0.0 else max(bytesPerSec, 0.0)
        return when {
            b >= 1_048_576 -> format("%.1f МБ/с", b / 1_048_576)
            b >= 1024 -> format("%.0f КБ/с", b / 1024)
            else -> format("%.0f Б/с", b)
        }
    }

    /** "1.5 ГБ", "300 МБ". */
    fun bytes(b: Long): String {
        val gb = b / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1) format("%.1f ГБ", gb) else format("%.0f МБ", b / 1024.0 / 1024.0)
    }

    /** "13 июля 2027"; "Бессрочно" for no expiry. */
    fun date(expireAtSec: Long): String {
        if (expireAtSec <= 0) return "Бессрочно"
        return SimpleDateFormat("d MMMM yyyy", RU).format(Date(expireAtSec * 1000))
    }

    /** "только что", "5 минут назад", "2 часа назад", "3 дня назад". */
    fun ago(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val seconds = (nowMillis - atMillis) / 1000
        if (seconds < 60) return "только что"
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
        val hours = minutes / 60
        if (hours < 24) return "$hours ${plural(hours, "час", "часа", "часов")} назад"
        val days = hours / 24
        return "$days ${plural(days, "день", "дня", "дней")} назад"
    }

    /** Whole days until [expireAtSec], never below zero. */
    fun daysLeft(expireAtSec: Long, nowSec: Long = System.currentTimeMillis() / 1000): Long =
        max((expireAtSec - nowSec) / 86_400, 0L)

    /** "199 ₽", "199.50 ₽". */
    fun price(rub: Double): String =
        if (!rub.isNaN() && !rub.isInfinite() && rub == floor(rub)) "${rub.toLong()} ₽" else format("%.2f ₽", rub)

    /** "устройство", "устройства", "устройств" for [n]. */
    fun devicesWord(n: Int): String = plural(n.toLong(), "устройство", "устройства", "устройств")

    /** "сервер", "сервера", "серверов" for [n]. */
    fun serversWord(n: Int): String = plural(n.toLong(), "сервер", "сервера", "серверов")

    /** "Финляндия" from "fi" or "FI"; the code itself when it isn't a country. */
    fun countryName(code: String): String {
        SUBDIVISIONS[code.lowercase()]?.let { return it }
        if (code.length != 2 || !code.all { it.isLetter() }) return code
        val name = runCatching { Locale.Builder().setRegion(code.uppercase()).build().getDisplayCountry(RU) }.getOrNull()
        if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) return code
        return name.replaceFirstChar { it.titlecase(RU) }
    }

    /** "01:02:03"; hours keep counting past a day. */
    fun timer(ms: Long): String {
        val s = max(ms, 0L) / 1000
        return format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /** "13 июля в 00:30". */
    fun dayTime(atMillis: Long): String = SimpleDateFormat("d MMMM 'в' HH:mm", RU).format(Date(atMillis))

    /** Russian plural: 1 минуту, 2 минуты, 5 минут, 11 минут, 21 минуту. */
    private fun plural(n: Long, one: String, few: String, many: String): String {
        val n100 = n % 100
        val n10 = n % 10
        return when {
            n100 in 11L..14L -> many
            n10 == 1L -> one
            n10 in 2L..4L -> few
            else -> many
        }
    }

    private fun format(pattern: String, vararg args: Any): String = String.format(Locale.ROOT, pattern, *args)

    /** flag-icons' flags that are not countries (no ISO code). */
    private val SUBDIVISIONS = mapOf("gb-eng" to "Англия", "gb-sct" to "Шотландия", "gb-wls" to "Уэльс", "gb-nir" to "Северная Ирландия")
}
