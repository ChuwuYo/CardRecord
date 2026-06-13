package com.shuaji.cards.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 锋锐感 Material 3 主题。
 *
 * 设计取向：冷调深蓝（银行业务的"信任感"）+ 高饱和的青/品红强调色 +
 * 锐角几何（[Shape.kt] 中定义）。允许 Android 12+ 使用动态色，
 * 否则回退到定制品牌色。
 */

private val BrandPrimary = Color(0xFF0061A4)
private val BrandPrimaryDark = Color(0xFF9DCAFF)
private val BrandSecondary = Color(0xFF00B5A5)
private val BrandSecondaryDark = Color(0xFF65D6C0)
private val BrandTertiary = Color(0xFFFF6E6E)
private val BrandTertiaryDark = Color(0xFFFFB3B3)
private val BrandSurfaceDark = Color(0xFF0F1F2E)
private val BrandSurfaceLight = Color(0xFFF6FAFF)

private val LightColors =
    lightColorScheme(
        primary = BrandPrimary,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD3E4FF),
        onPrimaryContainer = Color(0xFF001D36),
        secondary = BrandSecondary,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFB8F1E8),
        onSecondaryContainer = Color(0xFF00201C),
        tertiary = BrandTertiary,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDAD6),
        onTertiaryContainer = Color(0xFF410002),
        background = BrandSurfaceLight,
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
        primary = BrandPrimaryDark,
        onPrimary = Color(0xFF003259),
        primaryContainer = Color(0xFF00497D),
        onPrimaryContainer = Color(0xFFD3E4FF),
        secondary = BrandSecondaryDark,
        onSecondary = Color(0xFF003733),
        secondaryContainer = Color(0xFF00504A),
        onSecondaryContainer = Color(0xFFB8F1E8),
        tertiary = BrandTertiaryDark,
        onTertiary = Color(0xFF690005),
        tertiaryContainer = Color(0xFF93000A),
        onTertiaryContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0A121C),
        onBackground = Color(0xFFE2E2E6),
        surface = BrandSurfaceDark,
        onSurface = Color(0xFFE2E2E6),
        surfaceVariant = Color(0xFF42474E),
        onSurfaceVariant = Color(0xFFC2C7CF),
        outline = Color(0xFF8C9199),
        outlineVariant = Color(0xFF42474E),
    )

@Composable
fun ShuajiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
