package com.titanvps.app

import com.titanvps.app.data.Flags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlagsTest {

    private val england = "🏴󠁧󠁢󠁥󠁮󠁧󠁿"

    @Test
    fun splitsCountryFlag() {
        assertEquals("🇫🇮" to "Финляндия", Flags.split("🇫🇮 Финляндия"))
        assertEquals("🇫🇮" to "Финляндия 1", Flags.split("  🇫🇮Финляндия 1 "))
        assertEquals("fi", Flags.code("🇫🇮"))
        assertEquals("fi", Flags.codeOf("🇫🇮 Финляндия"))
        assertEquals("Финляндия", Flags.nameWithoutFlag("🇫🇮 Финляндия"))
    }

    @Test
    fun splitsEnglandsTagSequence() {
        assertEquals(england to "London", Flags.split("$england London"))
        assertEquals("gb-eng", Flags.code(england))
        assertEquals("gb-eng", Flags.codeOf("$england London"))
        assertEquals("London", Flags.nameWithoutFlag("$england London"))
    }

    @Test
    fun namesWithoutFlag() {
        assertEquals(null to "АВТО | Быстрые", Flags.split("АВТО | Быстрые"))
        assertEquals(null to "⚡ АВТО", Flags.split(" ⚡ АВТО"))
        assertNull(Flags.codeOf("АВТО | Быстрые"))
        assertNull(Flags.code("⚡"))
        // A black flag without its tag letters is no country.
        assertEquals(null to "🏴 Пират", Flags.split("🏴 Пират"))
    }

    @Test
    fun emojiRoundTrips() {
        assertEquals("🇫🇮", Flags.emojiOf("fi"))
        assertEquals("🇫🇮", Flags.emojiOf("FI"))
        assertEquals("fi", Flags.code(Flags.emojiOf("fi")))
        assertEquals("de", Flags.code(Flags.emojiOf("de")))
        assertEquals(england, Flags.emojiOf("gb-eng"))
        assertEquals("", Flags.emojiOf("finland"))
    }
}
