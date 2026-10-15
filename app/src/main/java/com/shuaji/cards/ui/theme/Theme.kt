package com.shuaji.cards.ui.theme

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.rememberDynamicColorScheme
import com.shuaji.cards.data.ColorSource
import com.shuaji.cards.data.ThemeMode
import com.shuaji.cards.data.ThemeSettings

const val DEFAULT_BRAND_PRIMARY_ARGB: Long = 0xFF0061A4

val DefaultBrandPrimary = Color(DEFAULT_BRAND_PRIMARY_ARGB)

@Composable
fun ShuajiTheme(
    settings: ThemeSettings = ThemeSettings(),
    darkTheme: Boolean = resolveDarkTheme(settings.themeMode, isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = resolveColorScheme(settings, darkTheme),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

internal fun resolveDarkTheme(
    themeMode: ThemeMode,
    isSystemDark: Boolean,
): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

internal fun resolveAppCompatNightMode(themeMode: ThemeMode): Int =
    when (themeMode) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

@Composable
private fun resolveColorScheme(
    settings: ThemeSettings,
    darkTheme: Boolean,
): ColorScheme =
    when (settings.colorSource) {
        ColorSource.SYSTEM_DYNAMIC ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                rememberDynamicColorScheme(seedColor = DefaultBrandPrimary, isDark = darkTheme, isAmoled = false)
            }

        ColorSource.CUSTOM ->
            rememberDynamicColorScheme(
                seedColor = parseSeedColor(settings.seedColorHex) ?: DefaultBrandPrimary,
                isDark = darkTheme,
                isAmoled = false,
            )
    }
