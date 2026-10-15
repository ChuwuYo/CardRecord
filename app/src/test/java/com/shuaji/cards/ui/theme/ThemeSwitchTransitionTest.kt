package com.shuaji.cards.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeSwitchTransitionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun contentFollowsTheLatestTargetEvenWhenSwitchedRapidly() {
        var target by mutableIntStateOf(1)
        val shownValues = mutableListOf<Int>()
        composeRule.setContent {
            ThemeSwitchTransition(target) { shown ->
                shownValues += shown
                Text("shown $shown", modifier = Modifier.fillMaxSize())
            }
        }
        composeRule.onNodeWithText("shown 1").assertExists()

        composeRule.runOnIdle { target = 2 }
        composeRule.runOnIdle { target = 3 }
        composeRule.mainClock.advanceTimeBy(THEME_REVEAL_DURATION_MILLIS * 3L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("shown 3").assertExists()
        assertEquals(3, shownValues.last())
    }
}
