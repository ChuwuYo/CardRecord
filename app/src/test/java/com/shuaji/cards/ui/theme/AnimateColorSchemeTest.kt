package com.shuaji.cards.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnimateColorSchemeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val light = lightColorScheme()
    private val dark = darkColorScheme()

    @Test
    fun allRolesMoveTogetherAndSettleOnTheTarget() {
        var target by mutableStateOf(light)
        lateinit var shown: ColorScheme
        composeRule.setContent { shown = animateColorScheme(target) }
        composeRule.waitForIdle()
        assertEquals(light.background, shown.background)

        composeRule.mainClock.autoAdvance = false
        target = dark
        composeRule.mainClock.advanceTimeBy(48)

        assertNotEquals(light.background, shown.background)
        assertNotEquals(dark.background, shown.background)
        assertNotEquals(light.surface, shown.surface)
        assertNotEquals(dark.surface, shown.surface)

        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals(dark.background, shown.background)
        assertEquals(dark.surface, shown.surface)
        assertEquals(dark.primary, shown.primary)
        assertEquals(dark.onTertiaryFixedVariant, shown.onTertiaryFixedVariant)
    }
}
