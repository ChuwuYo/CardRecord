package com.shuaji.cards

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ShuajiApplication::class, qualifiers = "zh-rCN")
class LanguageSwitchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun switchingLanguageUpdatesTextWithoutRecreatingTheActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        composeRule.waitUntilAtLeastOneExists(hasContentDescription("设置"), 15_000)

        val english =
            Configuration(activity.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags("en"))
            }
        controller.configurationChange(english)

        composeRule.waitUntilAtLeastOneExists(hasContentDescription("Settings"), 15_000)
        assertSame(activity, controller.get())
        controller.pause().stop().destroy()
    }
}
