package com.shuaji.cards

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.shuaji.cards.ui.ShuajiApp
import com.shuaji.cards.ui.theme.ShuajiTheme
import com.shuaji.cards.ui.theme.resolveAppCompatNightMode
import com.shuaji.cards.ui.theme.resolveDarkTheme

// AppCompatActivity 负责 Android 13 以下应用内语言的兼容切换。
// （AppCompatDelegate.setApplicationLocales）在 Android 13 以下依赖 AppCompat 的 Activity 委托
// 来应用 / 恢复语言。Compose 在 AppCompatActivity 上正常工作。
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepo = (application as ShuajiApplication).container.settings
        // 先建立 edge-to-edge 布局；Compose 首帧随后只更新图标明暗，避免 Insets 跳动。
        enableEdgeToEdge()
        setContent {
            // 不用默认主题猜首帧：DataStore 返回真实快照后，再以同一个亮暗判定
            // 同时驱动 Compose 与系统栏，避免两套判断短暂相反。
            val settings by settingsRepo.themeSettings.collectAsState(initial = null)
            settings?.let { loadedSettings ->
                val appCompatNightMode = resolveAppCompatNightMode(loadedSettings.themeMode)
                LaunchedEffect(loadedSettings.themeMode) {
                    // DataStore 是真源；同步缓存兼容升级前已有设置，并让后续窗口重建
                    // 在创建前就拿到正确的 values / values-night 资源。
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
                    ShuajiApp()
                }
            }
        }
    }
}
