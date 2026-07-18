package com.shuaji.cards.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import com.shuaji.cards.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test
    fun systemTheme_usesCurrentSystemAppearance() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, isSystemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, isSystemDark = false))
    }

    @Test
    fun explicitTheme_ignoresSystemAppearance() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, isSystemDark = false))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, isSystemDark = true))
    }

    @Test
    fun appCompatResources_followTheSameThemeMode() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, resolveAppCompatNightMode(ThemeMode.SYSTEM))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, resolveAppCompatNightMode(ThemeMode.LIGHT))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, resolveAppCompatNightMode(ThemeMode.DARK))
    }
}
