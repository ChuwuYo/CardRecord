package com.shuaji.cards

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shuaji.cards.core.AppLocales
import com.shuaji.cards.ui.ShuajiApp
import com.shuaji.cards.ui.ViewModelFactories
import com.shuaji.cards.ui.component.LocalUiDateFormatter
import com.shuaji.cards.ui.component.rememberUiDateFormatter
import com.shuaji.cards.ui.theme.AppThemeViewModel
import com.shuaji.cards.ui.theme.ShuajiTheme
import com.shuaji.cards.ui.theme.resolveAppCompatNightMode
import com.shuaji.cards.ui.theme.resolveDarkTheme

// AppCompatActivity 负责 Android 13 以下应用内语言的兼容切换。
// （AppCompatDelegate.setApplicationLocales）在 Android 13 以下依赖 AppCompat 的 Activity 委托
// 来应用 / 恢复语言。Compose 在 AppCompatActivity 上正常工作。
class MainActivity : AppCompatActivity() {
    private val themeViewModel: AppThemeViewModel by viewModels { ViewModelFactories.AppTheme }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLocales.record(this, AppCompatDelegate.getApplicationLocales())
        val settingsRepo = requireShuajiApplication().container.settings
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepo.themeSettings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { loadedSettings ->
                val appCompatNightMode = resolveAppCompatNightMode(loadedSettings.themeMode)
                LaunchedEffect(loadedSettings.themeMode) {
                    settingsRepo.syncStartupThemeMode(loadedSettings.themeMode)
                    if (AppCompatDelegate.getDefaultNightMode() != appCompatNightMode) {
                        AppCompatDelegate.setDefaultNightMode(appCompatNightMode)
                    }
                }
                val darkTheme = resolveDarkTheme(loadedSettings.themeMode, isSystemInDarkTheme())
                SideEffect {
                    val style =
                        if (darkTheme) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                            )
                        }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                ShuajiTheme(settings = loadedSettings, darkTheme = darkTheme) {
                    CompositionLocalProvider(LocalUiDateFormatter provides rememberUiDateFormatter()) {
                        ShuajiApp()
                    }
                }
            }
        }
    }
}
