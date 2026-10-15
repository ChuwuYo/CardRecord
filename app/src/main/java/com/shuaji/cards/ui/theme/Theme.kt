package com.shuaji.cards.ui.theme

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
        colorScheme = animateColorScheme(resolveColorScheme(settings, darkTheme)),
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
                remember(context, darkTheme) {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }
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

private const val M3_DEFAULT_EFFECTS_STIFFNESS = 1600f

private val colorSchemeSpec =
    spring<Color>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = M3_DEFAULT_EFFECTS_STIFFNESS)

@Composable
internal fun animateColorScheme(target: ColorScheme): ColorScheme {
    val transition = updateTransition(target, label = "colorScheme")

    @Composable
    fun color(role: (ColorScheme) -> Color): Color = transition.animateColor({ colorSchemeSpec }, label = "colorScheme") { role(it) }.value

    return ColorScheme(
        primary = color { it.primary },
        onPrimary = color { it.onPrimary },
        primaryContainer = color { it.primaryContainer },
        onPrimaryContainer = color { it.onPrimaryContainer },
        inversePrimary = color { it.inversePrimary },
        secondary = color { it.secondary },
        onSecondary = color { it.onSecondary },
        secondaryContainer = color { it.secondaryContainer },
        onSecondaryContainer = color { it.onSecondaryContainer },
        tertiary = color { it.tertiary },
        onTertiary = color { it.onTertiary },
        tertiaryContainer = color { it.tertiaryContainer },
        onTertiaryContainer = color { it.onTertiaryContainer },
        background = color { it.background },
        onBackground = color { it.onBackground },
        surface = color { it.surface },
        onSurface = color { it.onSurface },
        surfaceVariant = color { it.surfaceVariant },
        onSurfaceVariant = color { it.onSurfaceVariant },
        surfaceTint = color { it.surfaceTint },
        inverseSurface = color { it.inverseSurface },
        inverseOnSurface = color { it.inverseOnSurface },
        error = color { it.error },
        onError = color { it.onError },
        errorContainer = color { it.errorContainer },
        onErrorContainer = color { it.onErrorContainer },
        outline = color { it.outline },
        outlineVariant = color { it.outlineVariant },
        scrim = color { it.scrim },
        surfaceBright = color { it.surfaceBright },
        surfaceDim = color { it.surfaceDim },
        surfaceContainer = color { it.surfaceContainer },
        surfaceContainerHigh = color { it.surfaceContainerHigh },
        surfaceContainerHighest = color { it.surfaceContainerHighest },
        surfaceContainerLow = color { it.surfaceContainerLow },
        surfaceContainerLowest = color { it.surfaceContainerLowest },
        primaryFixed = color { it.primaryFixed },
        primaryFixedDim = color { it.primaryFixedDim },
        onPrimaryFixed = color { it.onPrimaryFixed },
        onPrimaryFixedVariant = color { it.onPrimaryFixedVariant },
        secondaryFixed = color { it.secondaryFixed },
        secondaryFixedDim = color { it.secondaryFixedDim },
        onSecondaryFixed = color { it.onSecondaryFixed },
        onSecondaryFixedVariant = color { it.onSecondaryFixedVariant },
        tertiaryFixed = color { it.tertiaryFixed },
        tertiaryFixedDim = color { it.tertiaryFixedDim },
        onTertiaryFixed = color { it.onTertiaryFixed },
        onTertiaryFixedVariant = color { it.onTertiaryFixedVariant },
    )
}
