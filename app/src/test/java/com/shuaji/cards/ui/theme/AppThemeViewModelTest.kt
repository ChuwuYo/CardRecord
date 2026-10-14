package com.shuaji.cards.ui.theme

import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.SettingsRepository
import com.shuaji.cards.data.ThemeMode
import com.shuaji.cards.data.ThemeSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class AppThemeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loaded = ThemeSettings(themeMode = ThemeMode.DARK)
    private val settingsRepo: SettingsRepository =
        mock { on { themeSettings } doReturn flowOf(loaded) }

    @Test
    fun theme_settings_keeps_loaded_value_after_collector_leaves() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val vm = AppThemeViewModel(settingsRepo)
            val collector = backgroundScope.launch { vm.themeSettings.collect {} }
            runCurrent()
            collector.cancel()
            advanceUntilIdle()

            assertEquals(loaded, vm.themeSettings.value)
        }
}
