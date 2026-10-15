package com.shuaji.cards

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.shuaji.cards.data.AppContainer
import com.shuaji.cards.data.DefaultAppContainer
import com.shuaji.cards.data.SharedPreferencesThemeModeStartupCache
import com.shuaji.cards.data.ThemeMode
import com.shuaji.cards.ui.theme.resolveAppCompatNightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ShuajiApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val startupThemeModeCache = SharedPreferencesThemeModeStartupCache(this)
        AppCompatDelegate.setDefaultNightMode(
            resolveAppCompatNightMode(startupThemeModeCache.read() ?: ThemeMode.SYSTEM),
        )
        container = DefaultAppContainer(this, startupThemeModeCache)
        container.startBackgroundWork(appScope)
    }
}

internal fun Context.requireShuajiApplication(): ShuajiApplication =
    applicationContext as? ShuajiApplication
        ?: error("Application must be ShuajiApplication")
