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
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_COLOR_SOURCE = stringPreferencesKey("color_source")
        val KEY_SEED_COLOR_HEX = stringPreferencesKey("seed_color_hex")
    }
}

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

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.SYSTEM_DYNAMIC,
    val seedColorHex: String? = null,
)

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "shuaji_prefs")
