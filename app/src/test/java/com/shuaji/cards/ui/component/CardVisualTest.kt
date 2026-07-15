package com.shuaji.cards.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shuaji.cards.data.local.ImageSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class CardVisualTest {
    @Test
    fun pureCard_hasBadgeButNoProviderDecorationOrBlades() {
        assertTrue(shouldShowNetworkBadge(networkPresent = true))
        assertFalse(shouldShowProviderDecoration(ImageSourceType.NONE, networkPresent = true))
        assertFalse(shouldShowBlades(ImageSourceType.NONE))
    }

    @Test
    fun providerCard_requiresValidNetworkForDecoration() {
        assertTrue(shouldShowProviderDecoration(ImageSourceType.PROVIDER, networkPresent = true))
        assertFalse(shouldShowProviderDecoration(ImageSourceType.PROVIDER, networkPresent = false))
    }

    @Test
    fun userImage_keepsImageDecorationAndMayShowBadge() {
        assertTrue(shouldShowBlades(ImageSourceType.USER))
        assertTrue(shouldShowNetworkBadge(networkPresent = true))
    }

    @Test
    fun cardSurfaceColor_usesSavedThemeColor() {
        val savedThemeColor = 0xFF2E7D32.toInt()

        assertEquals(Color(savedThemeColor), resolveCardSurfaceColor(savedThemeColor))
    }

    @Test
    fun networkLayout_scalesBetweenCompactAndLargeCards() {
        val compact = resolveCardNetworkVisualLayout(160.dp)
        val large = resolveCardNetworkVisualLayout(320.dp)

        assertEquals(36.dp, compact.badgeWidth)
        assertEquals(64.dp, large.badgeWidth)
        assertEquals(76.8.dp, compact.largeRing.diameter)
        assertEquals(54.4.dp, compact.smallRing.diameter)
        assertTrue(large.contentEndPadding > compact.contentEndPadding)
    }

    @Test
    fun networkLayout_hasExactlyTwoPartiallyOverlappingRings() {
        val layout = resolveCardNetworkVisualLayout(320.dp)
        val distance =
            hypot(
                layout.largeRing.centerX.value - layout.smallRing.centerX.value,
                layout.largeRing.centerY.value - layout.smallRing.centerY.value,
            )
        val sum = layout.largeRing.radius.value + layout.smallRing.radius.value
        val difference = abs(layout.largeRing.radius.value - layout.smallRing.radius.value)

        assertTrue(distance < sum)
        assertTrue(distance > difference)
    }
}
