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
        assertTrue(abs(compact.providerDecoration.watermarkRight.value - 3.2f) < 0.001f)
        assertTrue(abs(compact.providerDecoration.watermarkTop.value - 3.2f) < 0.001f)
        assertTrue(abs(large.providerDecoration.watermarkRight.value - 6.4f) < 0.001f)
        assertTrue(abs(large.providerDecoration.watermarkTop.value - 6.4f) < 0.001f)
        assertEquals(compact.badgeWidth + compact.badgeInset + 12.dp, compact.contentEndPadding)
        assertEquals(large.badgeWidth + large.badgeInset + 12.dp, large.contentEndPadding)
        assertEquals(76.8.dp, compact.providerDecoration.largeRing.diameter)
        assertEquals(54.4.dp, compact.providerDecoration.smallRing.diameter)
        assertTrue(large.contentEndPadding > compact.contentEndPadding)
    }

    @Test
    fun compactContent_usesBadgeInsetAndClearsBadgeVerticalBand() {
        val compactLayout = resolveCardNetworkVisualLayout(140.dp)

        assertEquals(
            CardContentPlacement(
                start = 6.dp,
                top = 6.dp,
                bottom = 34.dp,
            ),
            resolveCardContentPlacement(
                contentLayout = CardVisualContentLayout.COMPACT,
                orientation = CardOrientation.LANDSCAPE,
                networkLayout = compactLayout,
                badgeVisible = true,
                defaultPadding = 16.dp,
            ),
        )
    }

    @Test
    fun compactContent_withoutBadgeKeepsSymmetricEdgeInsets() {
        val compactLayout = resolveCardNetworkVisualLayout(140.dp)

        assertEquals(
            CardContentPlacement(
                start = 6.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
            resolveCardContentPlacement(
                contentLayout = CardVisualContentLayout.COMPACT,
                orientation = CardOrientation.LANDSCAPE,
                networkLayout = compactLayout,
                badgeVisible = false,
                defaultPadding = 16.dp,
            ),
        )
    }

    @Test
    fun compactContent_expandsUpperRowsAndKeepsNumberClearOfBadge() {
        val compactLayout =
            resolveCardNetworkVisualLayout(
                cardWidth = 160.dp,
                providerDecorationScale = COMPACT_PROVIDER_DECORATION_SCALE,
            )
        val largeLayout = resolveCardNetworkVisualLayout(320.dp)

        val compactPaddings =
            resolveCardContentEndPaddings(
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                networkLayout = compactLayout,
                contentLayout = CardVisualContentLayout.COMPACT,
                orientation = CardOrientation.LANDSCAPE,
                defaultPadding = 16.dp,
            )

        assertEquals(compactLayout.providerDecoration.motifEndPadding, compactPaddings.bankRow)
        assertDpClose(41.536f, compactPaddings.bankRow)

        assertEquals(
            CardContentEndPaddings(
                bankRow = compactLayout.providerDecoration.motifEndPadding,
                nameRow = 6.dp,
                numberRow = compactLayout.contentEndPadding,
            ),
            compactPaddings,
        )
        assertEquals(
            CardContentEndPaddings(
                bankRow = largeLayout.contentEndPadding,
                nameRow = largeLayout.contentEndPadding,
                numberRow = largeLayout.contentEndPadding,
            ),
            resolveCardContentEndPaddings(
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                networkLayout = largeLayout,
                contentLayout = CardVisualContentLayout.STANDARD,
                orientation = CardOrientation.LANDSCAPE,
                defaultPadding = 16.dp,
            ),
        )
        assertEquals(
            CardContentEndPaddings(
                bankRow = compactLayout.contentEndPadding,
                nameRow = compactLayout.contentEndPadding,
                numberRow = compactLayout.contentEndPadding,
            ),
            resolveCardContentEndPaddings(
                sourceType = ImageSourceType.PROVIDER,
                networkPresent = true,
                networkLayout = compactLayout,
                contentLayout = CardVisualContentLayout.STANDARD,
                orientation = CardOrientation.PORTRAIT,
                defaultPadding = 14.dp,
            ),
        )
        assertEquals(
            CardContentEndPaddings(bankRow = 16.dp, nameRow = 16.dp, numberRow = 16.dp),
            resolveCardContentEndPaddings(
                sourceType = ImageSourceType.USER,
                networkPresent = true,
                networkLayout = compactLayout,
                contentLayout = CardVisualContentLayout.STANDARD,
                orientation = CardOrientation.LANDSCAPE,
                defaultPadding = 16.dp,
            ),
        )
    }

    @Test
    fun compactMode_isExplicitAndDoesNotDependOnCardWidth() {
        val wideGridLayout = resolveCardNetworkVisualLayout(220.dp)

        assertEquals(
            wideGridLayout.badgeInset,
            resolveCardContentPlacement(
                contentLayout = CardVisualContentLayout.COMPACT,
                orientation = CardOrientation.LANDSCAPE,
                networkLayout = wideGridLayout,
                badgeVisible = true,
                defaultPadding = 16.dp,
            ).start,
        )
        assertEquals(
            14.dp,
            resolveCardContentPlacement(
                contentLayout = CardVisualContentLayout.COMPACT,
                orientation = CardOrientation.PORTRAIT,
                networkLayout = wideGridLayout,
                badgeVisible = true,
                defaultPadding = 14.dp,
            ).start,
        )
        assertTrue(isCompactCardContent(CardVisualContentLayout.COMPACT, CardOrientation.LANDSCAPE))
        assertFalse(isCompactCardContent(CardVisualContentLayout.COMPACT, CardOrientation.PORTRAIT))
        assertFalse(isCompactCardContent(CardVisualContentLayout.STANDARD, CardOrientation.LANDSCAPE))
    }

    @Test
    fun networkLayout_hasExactlyTwoPartiallyOverlappingRings() {
        val layout = resolveCardNetworkVisualLayout(320.dp)
        val distance =
            hypot(
                layout.providerDecoration.largeRing.centerX.value - layout.providerDecoration.smallRing.centerX.value,
                layout.providerDecoration.largeRing.centerY.value - layout.providerDecoration.smallRing.centerY.value,
            )
        val sum = layout.providerDecoration.largeRing.radius.value + layout.providerDecoration.smallRing.radius.value
        val difference = abs(layout.providerDecoration.largeRing.radius.value - layout.providerDecoration.smallRing.radius.value)

        assertTrue(distance < sum)
        assertTrue(distance > difference)
    }

    @Test
    fun compactDecoration_scalesWholeMotifFromTopEnd() {
        val cardWidth = 160.dp
        val base = resolveCardNetworkVisualLayout(cardWidth).providerDecoration
        val compact = base.scaledFromTopEnd(scale = 0.46f)

        assertDpClose(25.76f, compact.watermarkWidth)
        assertDpClose(17.664f, compact.watermarkHeight)
        assertDpClose(1.472f, compact.watermarkRight)
        assertDpClose(1.472f, compact.watermarkTop)
        assertDpClose(35.328f, compact.largeRing.diameter)
        assertDpClose(148.96f, compact.largeRing.centerX)
        assertDpClose(-3.68f, compact.largeRing.centerY)
        assertDpClose(25.024f, compact.smallRing.diameter)
        assertDpClose(134.976f, compact.smallRing.centerX)
        assertDpClose(11.776f, compact.smallRing.centerY)
        assertDpClose(0.46f, compact.ringStroke)

        val distance =
            hypot(
                compact.largeRing.centerX.value - compact.smallRing.centerX.value,
                compact.largeRing.centerY.value - compact.smallRing.centerY.value,
            )
        assertTrue(distance < compact.largeRing.radius.value + compact.smallRing.radius.value)
        assertTrue(distance > abs(compact.largeRing.radius.value - compact.smallRing.radius.value))
    }

    @Test
    fun compactDecoration_bankTextClearsWholeMotifAtGridWidths() {
        listOf(136.dp, 160.dp).forEach { cardWidth ->
            val decoration =
                resolveCardNetworkVisualLayout(
                    cardWidth = cardWidth,
                    providerDecorationScale = COMPACT_PROVIDER_DECORATION_SCALE,
                ).providerDecoration
            val textRightEdge = cardWidth - decoration.motifEndPadding
            val motifLeftEdge =
                minOf(
                    cardWidth - decoration.watermarkRight - decoration.watermarkWidth,
                    decoration.largeRing.centerX - decoration.largeRing.radius,
                    decoration.smallRing.centerX - decoration.smallRing.radius,
                )

            assertTrue(textRightEdge <= motifLeftEdge - 4.dp)
        }
    }

    private fun assertDpClose(
        expected: Float,
        actual: androidx.compose.ui.unit.Dp,
    ) {
        assertTrue("expected $expected.dp but was $actual", abs(actual.value - expected) < 0.001f)
    }
}
