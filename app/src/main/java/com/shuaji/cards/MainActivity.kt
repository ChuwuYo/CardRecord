package com.shuaji.cards

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.shuaji.cards.data.ThemeSettings
import com.shuaji.cards.ui.ShuajiApp
import com.shuaji.cards.ui.theme.ShuajiTheme

// 继承 AppCompatActivity（而非 ComponentActivity）：per-app language 的官方方案
// （AppCompatDelegate.setApplicationLocales）在 Android 13 以下依赖 AppCompat 的 Activity 委托
// 来应用 / 恢复语言。Compose 在 AppCompatActivity 上正常工作。
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepo = (application as ShuajiApplication).container.settings
        setContent {
            // 主题设置走 DataStore，全 app 持久化；
            // 后续 Settings 页改主题色 / 切换深浅模式时，这里会自动重组。
            val settings by settingsRepo.themeSettings.collectAsState(initial = ThemeSettings())
            ShuajiTheme(settings = settings) {
                ShuajiApp()
            }
        }
    }
}
