package com.shuaji.cards.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shuaji.cards.data.ColorSource
import com.shuaji.cards.data.ThemeMode
import com.shuaji.cards.data.ThemeSettings
import com.shuaji.cards.ui.component.LocalUiDateFormatter
import com.shuaji.cards.ui.component.rememberUiDateFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeSwitchScrollTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var settings by mutableStateOf(ThemeSettings(ThemeMode.LIGHT, ColorSource.SYSTEM_DYNAMIC, null))

    @Composable
    private fun Rows() {
        LazyColumn(Modifier.testTag(LIST)) {
            items(60) { Text("row $it") }
        }
    }

    private fun assertScrollKeptAcross(change: () -> Unit) {
        composeRule.onNodeWithTag(LIST).performScrollToIndex(30)
        composeRule.onNodeWithText("row 30").assertIsDisplayed()
        composeRule.runOnIdle(change)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("row 0").assertDoesNotExist()
        composeRule.onNodeWithText("row 30").assertIsDisplayed()
    }

    @Test
    fun themeOnly_colorSource() {
        composeRule.setContent { ShuajiTheme(settings = settings, darkTheme = false) { Rows() } }
        assertScrollKeptAcross { settings = settings.copy(colorSource = ColorSource.CUSTOM) }
    }

    @Test
    fun themeOnly_customToDynamic() {
        settings = settings.copy(colorSource = ColorSource.CUSTOM)
        composeRule.setContent { ShuajiTheme(settings = settings, darkTheme = false) { Rows() } }
        assertScrollKeptAcross { settings = settings.copy(colorSource = ColorSource.SYSTEM_DYNAMIC) }
    }

    @Test
    fun themeOnly_darkFlip() {
        var dark by mutableStateOf(false)
        composeRule.setContent { ShuajiTheme(settings = settings, darkTheme = dark) { Rows() } }
        assertScrollKeptAcross { dark = true }
    }

    @Test
    fun withFormatter_colorSource() {
        composeRule.setContent {
            ShuajiTheme(settings = settings, darkTheme = false) {
                CompositionLocalProvider(LocalUiDateFormatter provides rememberUiDateFormatter()) { Rows() }
            }
        }
        assertScrollKeptAcross { settings = settings.copy(colorSource = ColorSource.CUSTOM) }
    }

    @Test
    fun withNavHost_colorSource() {
        composeRule.setContent {
            ShuajiTheme(settings = settings, darkTheme = false) {
                CompositionLocalProvider(LocalUiDateFormatter provides rememberUiDateFormatter()) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "a") { composable("a") { Rows() } }
                }
            }
        }
        assertScrollKeptAcross { settings = settings.copy(colorSource = ColorSource.CUSTOM) }
    }

    private companion object {
        const val LIST = "list"
    }
}
