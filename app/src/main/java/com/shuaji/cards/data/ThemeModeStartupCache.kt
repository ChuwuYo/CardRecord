package com.shuaji.cards.data

import android.content.Context
import androidx.core.content.edit

/**
 * DataStore 仍是主题设置真源；这个同步小缓存只供 Application 在 Activity 创建前
 * 恢复明暗资源限定符，避免启动窗口按系统主题而 Compose 按应用主题。
 */
interface ThemeModeStartupCache {
    fun read(): ThemeMode?

    fun write(mode: ThemeMode)
}

class SharedPreferencesThemeModeStartupCache(
    context: Context,
) : ThemeModeStartupCache {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(): ThemeMode? =
        preferences
            .getString(KEY_THEME_MODE, null)
            ?.let(ThemeMode::fromKey)

    override fun write(mode: ThemeMode) {
        preferences.edit { putString(KEY_THEME_MODE, mode.key) }
    }

    private companion object {
        const val FILE_NAME = "startup_theme"
        const val KEY_THEME_MODE = "theme_mode"
    }
}

internal object NoOpThemeModeStartupCache : ThemeModeStartupCache {
    override fun read(): ThemeMode? = null

    override fun write(mode: ThemeMode) = Unit
}
