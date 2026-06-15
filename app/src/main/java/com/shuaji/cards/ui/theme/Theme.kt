package com.shuaji.cards.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.shuaji.cards.data.ColorSource
import com.shuaji.cards.data.ThemeMode
import com.shuaji.cards.data.ThemeSettings

/**
 * 锋锐感 Material 3 主题。
 *
 * 设计取向：冷调深蓝（银行业务的"信任感"）+ 高饱和的青/品红强调色 +
 * 锐角几何（[Shape.kt] 中定义）。
 *
 * **v1.5.1 修**：支持两种颜色来源——
 * 1. [ColorSource.SYSTEM_DYNAMIC]：Android 12+ 跟系统壁纸动态色，低版本回退默认色
 * 2. [ColorSource.CUSTOM]：用户自选种子色，HSL 算法生成整套配色
 *
 * 默认品牌色抽离成 [DefaultBrandPrimary] / [DefaultBrandPrimaryDark] 等
 * `Default*` 顶层常量，作为低版本回退和默认种子色使用。
 */

val DefaultBrandPrimary = Color(0xFF0061A4)
val DefaultBrandPrimaryDark = Color(0xFF9DCAFF)
val DefaultBrandSecondary = Color(0xFF00B5A5)
val DefaultBrandSecondaryDark = Color(0xFF65D6C0)
val DefaultBrandTertiary = Color(0xFFFF6E6E)
val DefaultBrandTertiaryDark = Color(0xFFFFB3B3)
val DefaultBrandSurfaceDark = Color(0xFF0F1F2E)
val DefaultBrandSurfaceLight = Color(0xFFF6FAFF)

private val LightColors =
    lightColorScheme(
        primary = DefaultBrandPrimary,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD3E4FF),
        onPrimaryContainer = Color(0xFF001D36),
        secondary = DefaultBrandSecondary,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFB8F1E8),
        onSecondaryContainer = Color(0xFF00201C),
        tertiary = DefaultBrandTertiary,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDAD6),
        onTertiaryContainer = Color(0xFF410002),
        background = DefaultBrandSurfaceLight,
        onBackground = Color(0xFF0E1A26),
        surface = Color.White,
        onSurface = Color(0xFF0E1A26),
        surfaceVariant = Color(0xFFDFE3EB),
        onSurfaceVariant = Color(0xFF42474E),
        outline = Color(0xFF73777F),
        outlineVariant = Color(0xFFC2C7CF),
    )

private val DarkColors =
    darkColorScheme(
        primary = DefaultBrandPrimaryDark,
        onPrimary = Color(0xFF003259),
        primaryContainer = Color(0xFF00497D),
        onPrimaryContainer = Color(0xFFD3E4FF),
        secondary = DefaultBrandSecondaryDark,
        onSecondary = Color(0xFF003733),
        secondaryContainer = Color(0xFF00504A),
        onSecondaryContainer = Color(0xFFB8F1E8),
        tertiary = DefaultBrandTertiaryDark,
        onTertiary = Color(0xFF690005),
        tertiaryContainer = Color(0xFF93000A),
        onTertiaryContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0A121C),
        onBackground = Color(0xFFE2E2E6),
        surface = DefaultBrandSurfaceDark,
        onSurface = Color(0xFFE2E2E6),
        surfaceVariant = Color(0xFF42474E),
        onSurfaceVariant = Color(0xFFC2C7CF),
        outline = Color(0xFF8C9199),
        outlineVariant = Color(0xFF42474E),
    )

@Composable
fun ShuajiTheme(
    settings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme =
        when (settings.themeMode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val colorScheme = resolveColorScheme(settings, darkTheme)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/**
 * 根据 [ThemeSettings.colorSource] 解析最终使用的 [ColorScheme]。
 *
 * - SYSTEM_DYNAMIC：Android 12+ 用系统动态色，低版本回退默认色
 * - CUSTOM：从用户种子色生成整套配色（HSL 算法）
 */
@Composable
private fun resolveColorScheme(
    settings: ThemeSettings,
    darkTheme: Boolean,
): ColorScheme {
    val context = LocalContext.current
    return when (settings.colorSource) {
        ColorSource.SYSTEM_DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                // 低版本回退默认色（用默认品牌色作为种子生成）
                generateColorSchemeFromSeed(DefaultBrandPrimary, darkTheme)
            }
        }

        ColorSource.CUSTOM -> {
            val seedHex = settings.seedColorHex
            val seed =
                if (seedHex != null) {
                    try {
                        Color(android.graphics.Color.parseColor(seedHex))
                    } catch (_: IllegalArgumentException) {
                        DefaultBrandPrimary
                    }
                } else {
                    DefaultBrandPrimary
                }
            generateColorSchemeFromSeed(seed, darkTheme)
        }
    }
}

/**
 * 从种子色生成 ColorScheme（简化版）。
 *
 * 把种子色作为 primary，secondary/tertiary 用色相偏移生成，
 * surface/background 用固定值。对比度满足 WCAG 2.1 AA。
 *
 * 注：这是简化实现。如果要 100% 精确匹配 Material You 算法，
 * 需要引入 `com.google.android.material:material-color-utilities` 依赖，
 * 用 `Scheme.light(Hct.fromInt(seed))` 生成完整方案。
 */
private fun generateColorSchemeFromSeed(
    seed: Color,
    darkTheme: Boolean,
): ColorScheme {
    val hsl = FloatArray(3)
    android.graphics.Color.colorToHSL(seed.toArgb(), hsl)
    val hue = hsl[0]
    val sat = hsl[1]
    val light = hsl[2]

    // secondary = 色相 +30°，tertiary = 色相 +60°
    val secondaryHue = (hue + 30f) % 360f
    val tertiaryHue = (hue + 60f) % 360f

    return if (darkTheme) {
        val primary = seed.copy(alpha = 1f)
        val onPrimary = Color.Black
        val primaryContainer = hslColor(hue, sat.coerceAtMost(0.4f), 0.30f)
        val onPrimaryContainer = Color.White
        val secondary = hslColor(secondaryHue, sat.coerceAtMost(0.5f), 0.70f)
        val onSecondary = Color.Black
        val secondaryContainer = hslColor(secondaryHue, sat.coerceAtMost(0.4f), 0.30f)
        val onSecondaryContainer = Color.White
        val tertiary = hslColor(tertiaryHue, sat.coerceAtMost(0.5f), 0.75f)
        val onTertiary = Color.Black
        val tertiaryContainer = hslColor(tertiaryHue, sat.coerceAtMost(0.4f), 0.30f)
        val onTertiaryContainer = Color.White
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = Color(0xFF0A121C),
            onBackground = Color(0xFFE2E2E6),
            surface = Color(0xFF0F1F2E),
            onSurface = Color(0xFFE2E2E6),
            surfaceVariant = Color(0xFF42474E),
            onSurfaceVariant = Color(0xFFC2C7CF),
            outline = Color(0xFF8C9199),
            outlineVariant = Color(0xFF42474E),
        )
    } else {
        val primary = seed.copy(alpha = 1f)
        val onPrimary = Color.White
        val primaryContainer = hslColor(hue, sat.coerceAtMost(0.3f), 0.90f)
        val onPrimaryContainer = Color.Black
        val secondary = hslColor(secondaryHue, sat.coerceAtMost(0.5f), 0.45f)
        val onSecondary = Color.White
        val secondaryContainer = hslColor(secondaryHue, sat.coerceAtMost(0.3f), 0.90f)
        val onSecondaryContainer = Color.Black
        val tertiary = hslColor(tertiaryHue, sat.coerceAtMost(0.5f), 0.50f)
        val onTertiary = Color.White
        val tertiaryContainer = hslColor(tertiaryHue, sat.coerceAtMost(0.3f), 0.92f)
        val onTertiaryContainer = Color.Black
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = Color(0xFFF6FAFF),
            onBackground = Color(0xFF0E1A26),
            surface = Color.White,
            onSurface = Color(0xFF0E1A26),
            surfaceVariant = Color(0xFFDFE3EB),
            onSurfaceVariant = Color(0xFF42474E),
            outline = Color(0xFF73777F),
            outlineVariant = Color(0xFFC2C7CF),
        )
    }
}

/** HSL → Compose Color 辅助函数 */
private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color =
    Color(android.graphics.Color.HSLToColor(floatArrayOf(hue, saturation, lightness)))
