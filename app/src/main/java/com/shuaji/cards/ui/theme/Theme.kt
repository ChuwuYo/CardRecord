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

/*
 * 锋锐感 Material 3 主题。支持两种颜色来源：
 *   1. ColorSource.SYSTEM_DYNAMIC：Android 12+ 跟随系统壁纸动态色，低版本回退默认色
 *   2. ColorSource.CUSTOM：用户自选种子色，由 MaterialKolor 按 Material You（HCT tonal palette）生成整套配色
 *
 * 自定义种子色通过 MaterialKolor 生成 ColorScheme，避免在应用内维护另一套
 * HSL 推导规则。
 */

/** 默认品牌主色的 ARGB 单一真源。 */
const val DEFAULT_BRAND_PRIMARY_ARGB: Long = 0xFF0061A4

/** 默认品牌主色，用作动态色回退与默认种子色。 */
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

/** 把持久化选择与系统外观归一化为唯一的生效亮暗状态。 */
internal fun resolveDarkTheme(
    themeMode: ThemeMode,
    isSystemDark: Boolean,
): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

/** 让 AppCompat 的资源限定符与 Compose 使用同一个主题模式。 */
internal fun resolveAppCompatNightMode(themeMode: ThemeMode): Int =
    when (themeMode) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

/**
 * 根据 [ThemeSettings.colorSource] 解析最终使用的 [ColorScheme]。
 *
 * - SYSTEM_DYNAMIC：Android 12+ 用系统动态色，低版本用默认种子色生成
 * - CUSTOM：从用户种子色生成整套配色
 */
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
