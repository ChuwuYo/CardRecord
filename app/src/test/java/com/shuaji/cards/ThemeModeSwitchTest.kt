package com.shuaji.cards

import android.content.res.Configuration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ShuajiApplication::class, qualifiers = "zh-rCN")
class ThemeModeSwitchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchingThemeModeKeepsTheActivity() {
        composeRule.waitUntilAtLeastOneExists(hasContentDescription("设置"), 15_000)
        composeRule.onAllNodesWithContentDescription("设置")[0].performClick()
        composeRule.waitUntilAtLeastOneExists(hasScrollAction(), 15_000)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("主题模式"))
        composeRule.waitUntilAtLeastOneExists(hasText("跟随系统"), 15_000)
        val activity = composeRule.activity

        composeRule.onNodeWithText("主题模式").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("浅色"), 15_000)
        composeRule.onNodeWithText("主题模式").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("深色"), 15_000)

        assertSame(activity, composeRule.activity)
        assertEquals(
            Configuration.UI_MODE_NIGHT_YES,
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )
    }
}
