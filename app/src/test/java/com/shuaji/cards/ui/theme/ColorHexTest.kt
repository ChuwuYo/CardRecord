package com.shuaji.cards.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [parseSeedColor] 的解析与容错单测。 */
class ColorHexTest {
    @Test
    fun parses_six_digit_hex_as_opaque() {
        val c = requireNotNull(parseSeedColor("#0061A4"))
        assertEquals(0x00 / 255f, c.red, 0.01f)
        assertEquals(0x61 / 255f, c.green, 0.01f)
        assertEquals(0xA4 / 255f, c.blue, 0.01f)
        assertEquals(1f, c.alpha, 0.001f)
    }

    @Test
    fun accepts_no_hash_prefix_and_eight_digit_alpha() {
        assertEquals(Color(0xFF0061A4), parseSeedColor("0061A4"))
        assertEquals(Color(0x800061A4), parseSeedColor("#800061A4"))
    }

    @Test
    fun returns_null_for_invalid_input() {
        assertNull(parseSeedColor(null))
        assertNull(parseSeedColor("#12"))
        assertNull(parseSeedColor("#GGGGGG"))
    }
}
