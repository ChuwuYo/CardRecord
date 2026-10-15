package com.shuaji.cards.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollingNumberTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settlesOnTheLatestValueAfterRapidChangesAcrossDigitCounts() {
        var value by mutableIntStateOf(9)
        composeRule.setContent {
            RollingNumber(value = value, style = TextStyle())
            Text("end")
        }

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { value = 10 }
        composeRule.mainClock.advanceTimeBy(ROLLING_DIGIT_DURATION_MILLIS / 3L)
        composeRule.runOnUiThread { value = 8 }
        composeRule.mainClock.advanceTimeBy(ROLLING_DIGIT_DURATION_MILLIS * 4L)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(1, composeRule.onAllNodesWithText("8").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("1").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("0").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("9").fetchSemanticsNodes().size)
    }
}
