package com.shuaji.cards

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ShuajiApplication::class, qualifiers = "zh-rCN-w360dp-h480dp")
class SettingsScrollIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun openSettingsScrolledToBottom() {
        composeRule.waitUntilAtLeastOneExists(
            hasText("设置").or(
                androidx.compose.ui.test
                    .hasContentDescription("设置"),
            ),
            10_000,
        )
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("颜色来源"), 10_000)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("语言"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("语言").assertIsDisplayed()
        println("DIAG before: " + composeRule.onNode(hasScrollAction()).fetchSemanticsNode().config)
    }

    private fun report(label: String) {
        composeRule.waitForIdle()
        val node = composeRule.onNode(hasScrollAction()).fetchSemanticsNode()
        println("DIAG $label: " + node.config)
        composeRule.onNodeWithText("语言").assertIsDisplayed()
    }

    @Test
    fun colorSourceSwitch_keepsSettingsScroll() {
        openSettingsScrolledToBottom()
        composeRule.onNodeWithText("颜色来源").performClick()
        Thread.sleep(500)
        report("after color source")
    }

    @Test
    fun themeModeSwitch_keepsSettingsScroll() {
        openSettingsScrolledToBottom()
        composeRule.onNodeWithText("主题模式").performClick()
        Thread.sleep(500)
        composeRule.waitUntilAtLeastOneExists(hasText("颜色来源"), 10_000)
        report("after theme mode")
    }
}
