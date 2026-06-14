package com.shuaji.cards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.shuaji.cards.data.ThemeSettings
import com.shuaji.cards.ui.ShuajiApp
import com.shuaji.cards.ui.theme.ShuajiTheme

class MainActivity : ComponentActivity() {
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
