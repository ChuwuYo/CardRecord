package com.shuaji.cards

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import org.junit.Assert.fail
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

    private fun dump(): String =
        runCatching { composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = 6) }
            .getOrElse { "no root: $it" }

    private fun waitFor(
        matcher: SemanticsMatcher,
        label: String,
    ) {
        try {
            composeRule.waitUntilAtLeastOneExists(matcher, 15_000)
        } catch (e: Throwable) {
            fail("DIAG wait $label failed: $e\n${dump()}")
        }
    }

    private fun scrollValue(): Float =
        composeRule
            .onNode(hasScrollAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun openSettingsScrolledToBottom(): Float {
        waitFor(hasContentDescription("设置"), "card list")
        composeRule.onAllNodesWithContentDescription("设置")[0].performClick()
        waitFor(hasText("颜色来源"), "settings")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("语言"))
        composeRule.waitForIdle()
        return scrollValue()
    }

    private fun check(
        label: String,
        before: Float,
    ) {
        waitFor(hasText("颜色来源"), "settings after $label")
        Thread.sleep(300)
        composeRule.waitForIdle()
        val after = scrollValue()
        if (after != before) fail("DIAG $label before=$before after=$after\n${dump()}")
    }

    @Test
    fun colorSourceSwitch() {
        val before = openSettingsScrolledToBottom()
        composeRule.onNodeWithText("颜色来源").performClick()
        check("color source", before)
    }

    @Test
    fun themeModeSwitch() {
        val before = openSettingsScrolledToBottom()
        composeRule.onNodeWithText("主题模式").performClick()
        Thread.sleep(500)
        check("theme mode", before)
    }

    @Test
    fun plainRecreate() {
        val before = openSettingsScrolledToBottom()
        composeRule.activityRule.scenario.recreate()
        check("recreate", before)
    }
}
