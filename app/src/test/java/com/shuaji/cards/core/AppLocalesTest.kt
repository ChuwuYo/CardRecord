package com.shuaji.cards.core

import android.content.Context
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en")
class AppLocalesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun recordedAppLanguage_overridesSystemLanguageBelowApi33() {
        AppLocales.record(context, LocaleListCompat.forLanguageTags("zh-Hans"))

        assertEquals("未知原因", AppLocales.localize(context).getString(R.string.common_unknown_reason))
    }

    @Test
    fun followSystem_keepsSystemLanguage() {
        AppLocales.record(context, LocaleListCompat.getEmptyLocaleList())

        assertEquals("Unknown reason", AppLocales.localize(context).getString(R.string.common_unknown_reason))
    }
}
