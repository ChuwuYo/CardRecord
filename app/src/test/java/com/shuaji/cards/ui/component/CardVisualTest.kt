package com.shuaji.cards.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.ImageSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class CardVisualTest {
    @Test
    fun pureCard_hasBadgeButNoProviderDecoration() {
        assertTrue(shouldShowNetworkBadge(ImageSourceType.NONE, networkPresent = true))
        assertFalse(shouldShowProviderDecoration(ImageSourceType.NONE, networkPresent = true))
    }

    @Test
    fun providerCard_requiresValidNetworkForDecoration() {
        assertTrue(shouldShowProviderDecoration(ImageSourceType.PROVIDER, networkPresent = true))
        assertFalse(shouldShowProviderDecoration(ImageSourceType.PROVIDER, networkPresent = false))
    }

    @Test
    fun userImage_neverAddsNetworkVisuals() {
        assertFalse(shouldShowProviderDecoration(ImageSourceType.USER, networkPresent = true))
        assertFalse(shouldShowNetworkBadge(ImageSourceType.USER, networkPresent = true))
    }

    @Test
    fun cardSurfaceColors_areOpaqueAndDerivedFromSavedThemeColor() {
        val savedThemeColor = 0xFF2E7D32.toInt()
        val colors = resolveCardSurfaceColors(savedThemeColor)

        assertEquals(Color(savedThemeColor), colors.first())
        assertTrue(colors.all { it.alpha == 1f })
    }

    @Test
    fun networkLayout_scalesBetweenCompactAndLargeCards() {
        val compact = resolveCardNetworkVisualLayout(160.dp)
        val large = resolveCardNetworkVisualLayout(320.dp)

        assertEquals(36.dp, compact.badgeWidth)
        assertEquals(64.dp, large.badgeWidth)
        assertEquals(6.dp, compact.badgeInset)
        assertEquals(8.dp, large.badgeInset)
        assertTrue(abs(compact.watermarkRight.value - 3.2f) < 0.001f)
        assertTrue(abs(compact.watermarkTop.value - 3.2f) < 0.001f)
        assertTrue(abs(large.watermarkRight.value - 6.4f) < 0.001f)
        assertTrue(abs(large.watermarkTop.value - 6.4f) < 0.001f)
        assertEquals(compact.badgeWidth + compact.badgeInset + 12.dp, compact.contentEndPadding)
        assertEquals(large.badgeWidth + large.badgeInset + 12.dp, large.contentEndPadding)
        assertEquals(76.8.dp, compact.largeRing.diameter)
        assertEquals(54.4.dp, compact.smallRing.diameter)
        assertTrue(large.contentEndPadding > compact.contentEndPadding)
    }

    @Test
    fun compactTextLift_requiresLandscapeBadgeAndCompactWidth() {
        assertEquals(
            8.dp,
            resolveCardTextLift(
                orientation = CardOrientation.LANDSCAPE,
                sourceType = ImageSourceType.NONE,
                networkPresent = true,
                cardWidth = 160.dp,
            ),
        )
        assertEquals(
            8.dp,
            resolveCardTextLift(
                orientation = CardOrientation.LANDSCAPE,
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                cardWidth = 180.dp,
            ),
        )
        assertEquals(
            0.dp,
            resolveCardTextLift(
                orientation = CardOrientation.LANDSCAPE,
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                cardWidth = 320.dp,
            ),
        )
        assertEquals(
            0.dp,
            resolveCardTextLift(
                orientation = CardOrientation.PORTRAIT,
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                cardWidth = 160.dp,
            ),
        )
        assertEquals(
            0.dp,
            resolveCardTextLift(
                orientation = CardOrientation.LANDSCAPE,
                sourceType = ImageSourceType.USER,
                networkPresent = true,
                cardWidth = 160.dp,
            ),
        )
        assertEquals(
            0.dp,
            resolveCardTextLift(
                orientation = CardOrientation.LANDSCAPE,
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = false,
                cardWidth = 160.dp,
            ),
        )
    }

    @Test
    fun compactLiftedText_expandsOnlyUpperRowsAndKeepsNumberClearOfBadge() {
        val compactLayout = resolveCardNetworkVisualLayout(160.dp)
        val largeLayout = resolveCardNetworkVisualLayout(320.dp)

        assertEquals(
            CardContentEndPaddings(upperRows = 16.dp, numberRow = compactLayout.contentEndPadding),
            resolveCardContentEndPaddings(
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                networkLayout = compactLayout,
                textLift = 8.dp,
                defaultPadding = 16.dp,
            ),
        )
        assertEquals(
            CardContentEndPaddings(
                upperRows = largeLayout.contentEndPadding,
                numberRow = largeLayout.contentEndPadding,
            ),
            resolveCardContentEndPaddings(
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                networkLayout = largeLayout,
                textLift = 0.dp,
                defaultPadding = 16.dp,
            ),
        )
        assertEquals(
            CardContentEndPaddings(
                upperRows = compactLayout.contentEndPadding,
                numberRow = compactLayout.contentEndPadding,
            ),
            resolveCardContentEndPaddings(
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                networkLayout = compactLayout,
                textLift = 0.dp,
                defaultPadding = 14.dp,
            ),
        )
        assertEquals(
            CardContentEndPaddings(upperRows = 16.dp, numberRow = 16.dp),
            resolveCardContentEndPaddings(
                sourceType = ImageSourceType.USER,
                networkPresent = true,
                networkLayout = compactLayout,
                textLift = 0.dp,
                defaultPadding = 16.dp,
            ),
        )
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
