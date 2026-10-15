package com.shuaji.cards

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertTrue
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
    }

    private fun scrollValue(): Float =
        composeRule
            .onNode(hasScrollAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun report(
        label: String,
        before: Float,
    ) {
        composeRule.waitForIdle()
        val after = scrollValue()
        assertTrue("DIAG $label before=$before after=$after", after > 0f)
    }

    @Test
    fun colorSourceSwitch_keepsSettingsScroll() {
        openSettingsScrolledToBottom()
        val before = scrollValue()
        composeRule.onNodeWithText("颜色来源").performClick()
        Thread.sleep(500)
        report("after color source", before)
    }

    @Test
    fun themeModeSwitch_keepsSettingsScroll() {
        openSettingsScrolledToBottom()
        val before = scrollValue()
        composeRule.onNodeWithText("主题模式").performClick()
        Thread.sleep(500)
        composeRule.waitUntilAtLeastOneExists(hasText("颜色来源"), 10_000)
        report("after theme mode", before)
    }

    @Test
    fun plainRecreate_keepsSettingsScroll() {
        openSettingsScrolledToBottom()
        val before = scrollValue()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntilAtLeastOneExists(hasText("颜色来源"), 10_000)
        report("after plain recreate", before)
    }

    @Test
    fun plainRecreate_keepsCardListDestination() {
        openSettingsScrolledToBottom()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.waitForIdle()
        assertTrue(
            "DIAG settings destination lost after recreate",
            composeRule.onAllNodes(hasText("颜色来源")).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
