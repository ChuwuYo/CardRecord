package com.shuaji.cards.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全局用户偏好（主题模式 / 动态色 / 后续可拓展语言等）。
 *
 * 走 [DataStore Preferences] 持久化，跨进程安全。
 * 通过 [appDataStore] 顶层扩展拿到全局单例。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val themeSettings: Flow<ThemeSettings> =
        dataStore.data.map { prefs ->
            ThemeSettings(
                themeMode = ThemeMode.fromKey(prefs[KEY_THEME_MODE]),
                useDynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.key }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}

/** 主题模式：跟随系统 / 始终浅色 / 始终深色 */
enum class ThemeMode(
    val key: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** 当前生效的主题配置快照 */
data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
)

/** 全局 DataStore：单实例，进程内共享 */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "shuaji_prefs")
