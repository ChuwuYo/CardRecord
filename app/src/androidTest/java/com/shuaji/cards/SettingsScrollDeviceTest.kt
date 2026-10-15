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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsScrollDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun s(id: Int) = composeRule.activity.getString(id)

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
        waitFor(hasContentDescription(s(R.string.settings_title)), "card list")
        composeRule.onAllNodesWithContentDescription(s(R.string.settings_title))[0].performClick()
        waitFor(hasText(s(R.string.settings_section_data)), "settings")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(s(R.string.settings_language)))
        composeRule.waitForIdle()
        return scrollValue()
    }

    private fun check(
        label: String,
        before: Float,
    ) {
        waitFor(hasScrollAction(), "settings after $label")
        Thread.sleep(300)
        composeRule.waitForIdle()
        val after = scrollValue()
        fail("DIAG RESULT ${if (after == before && before > 0f) "KEPT" else "LOST"} $label before=$before after=$after\n${dump()}")
    }

    @Test
    fun colorSourceSwitch() {
        val before = openSettingsScrolledToBottom()
        composeRule.onNodeWithText(s(R.string.settings_color_source)).performClick()
        check("color source", before)
    }

    @Test
    fun themeModeSwitch() {
        val before = openSettingsScrolledToBottom()
        composeRule.onNodeWithText(s(R.string.settings_theme_mode)).performClick()
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
