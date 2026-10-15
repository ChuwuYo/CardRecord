package com.shuaji.cards

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
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
import com.shuaji.cards.ui.theme.ResolvedAppearance
import com.shuaji.cards.ui.theme.ShuajiTheme
import com.shuaji.cards.ui.theme.ThemeSwitchTransition
import com.shuaji.cards.ui.theme.resolveAppCompatNightMode
import com.shuaji.cards.ui.theme.resolveDarkTheme

// AppCompatActivity 负责 Android 13 以下应用内语言的兼容切换。
// （AppCompatDelegate.setApplicationLocales）在 Android 13 以下依赖 AppCompat 的 Activity 委托
// 来应用 / 恢复语言。Compose 在 AppCompatActivity 上正常工作。
class MainActivity : AppCompatActivity() {
    private val themeViewModel: AppThemeViewModel by viewModels { ViewModelFactories.AppTheme }
    private var composedLocales: LocaleList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLocales.record(this, AppCompatDelegate.getApplicationLocales())
        composedLocales = resources.configuration.locales
        val settingsRepo = requireShuajiApplication().container.settings
        enableEdgeToEdge()
        setContent {
            val settings by themeViewModel.themeSettings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            settings?.let { latestSettings ->
                val latestAppearance =
                    ResolvedAppearance(latestSettings, resolveDarkTheme(latestSettings.themeMode, systemDark))
                ThemeSwitchTransition(latestAppearance) { (loadedSettings, darkTheme) ->
                    val appCompatNightMode = resolveAppCompatNightMode(loadedSettings.themeMode)
                    LaunchedEffect(loadedSettings.themeMode) {
                        settingsRepo.syncStartupThemeMode(loadedSettings.themeMode)
                        if (AppCompatDelegate.getDefaultNightMode() != appCompatNightMode) {
                            AppCompatDelegate.setDefaultNightMode(appCompatNightMode)
                        }
                    }
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocales.record(this, AppCompatDelegate.getApplicationLocales())
        val localesChanged = newConfig.locales != composedLocales
        composedLocales = newConfig.locales
        // Android 12 及以下 AppCompat 只回调 Activity，不会把新语言分发给 Compose 的 View，只能重建。
        if (localesChanged && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate()
    }
}
