package com.shuaji.cards.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CardVisualTest {
    @Test
    fun cardSurfaceColor_usesSavedThemeColor() {
        val savedThemeColor = 0xFF2E7D32.toInt()

        assertEquals(Color(savedThemeColor), resolveCardSurfaceColor(savedThemeColor))
    }

    @Test
    fun compactCardHeader_stacksIssuerAndNetwork() {
        assertEquals(false, shouldStackCardHeader(220.dp))
        assertEquals(true, shouldStackCardHeader(128.dp))
    }
}
