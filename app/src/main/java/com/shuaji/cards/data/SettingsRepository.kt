package com.shuaji.cards.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 应用内用户偏好（主题模式、颜色来源和自定义种子色）。
 *
 * [appDataStore] 保证进程内单例；旧版 `dynamic_color` 只保留读取兼容，
 * 新写入统一使用稳定的 `color_source` key。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val startupThemeModeCache: ThemeModeStartupCache = NoOpThemeModeStartupCache,
) {
    val themeSettings: Flow<ThemeSettings> =
        dataStore.data
            .catch { failure ->
                if (failure is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw failure
                }
            }.map { prefs ->
                val colorSource =
                    prefs[KEY_COLOR_SOURCE]?.let { ColorSource.fromKey(it) }
                        ?: run {
                            // 旧版迁移：useDynamicColor=true → SYSTEM_DYNAMIC，false → CUSTOM
                            val oldDynamic = prefs[KEY_DYNAMIC_COLOR]
                            when (oldDynamic) {
                                true -> ColorSource.SYSTEM_DYNAMIC
                                false -> ColorSource.CUSTOM
                                else -> ColorSource.SYSTEM_DYNAMIC
                            }
                        }
                ThemeSettings(
                    themeMode = ThemeMode.fromKey(prefs[KEY_THEME_MODE]),
                    colorSource = colorSource,
                    seedColorHex = prefs[KEY_SEED_COLOR_HEX],
                )
            }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.key }
        startupThemeModeCache.write(mode)
    }

    /** 首次升级到启动缓存时，用 DataStore 真值补齐缓存；后续写入仍由 [setThemeMode] 同步。 */
    fun syncStartupThemeMode(mode: ThemeMode) {
        if (startupThemeModeCache.read() != mode) startupThemeModeCache.write(mode)
    }

    suspend fun setColorSource(source: ColorSource) {
        dataStore.edit { it[KEY_COLOR_SOURCE] = source.key }
    }

    suspend fun setSeedColorHex(hex: String?) {
        dataStore.edit {
            if (hex != null) {
                it[KEY_SEED_COLOR_HEX] = hex
            } else {
                it.remove(KEY_SEED_COLOR_HEX)
            }
        }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color") // 旧字段，兼容读取
        val KEY_COLOR_SOURCE = stringPreferencesKey("color_source")
        val KEY_SEED_COLOR_HEX = stringPreferencesKey("seed_color_hex")
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

/** 颜色来源：系统动态色 / 用户自定义 */
enum class ColorSource(
    val key: String,
) {
    SYSTEM_DYNAMIC("system_dynamic"),
    CUSTOM("custom"),
    ;

    companion object {
        fun fromKey(key: String?): ColorSource = entries.firstOrNull { it.key == key } ?: SYSTEM_DYNAMIC
    }
}

/** 当前生效的主题配置快照 */
data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.SYSTEM_DYNAMIC,
    val seedColorHex: String? = null,
)

/** 全局 DataStore：单实例，进程内共享 */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "shuaji_prefs")
