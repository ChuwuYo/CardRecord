package com.shuaji.cards.ui.theme

import android.os.Build
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
 * 种子色 → ColorScheme 不再自行用 HSL 近似，而是交给成熟的 MaterialKolor 库
 * （内部即 Google material-color-utilities 的官方 Material You 算法），保证整套
 * primary/secondary/tertiary/中性面/容器色都符合 MD3 规范并随种子色协调变化。
 */

/** 默认品牌主色，用作动态色回退与默认种子色。 */
val DefaultBrandPrimary = Color(0xFF0061A4)

@Composable
fun ShuajiTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (settings.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    MaterialTheme(
        colorScheme = resolveColorScheme(settings, darkTheme),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
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
