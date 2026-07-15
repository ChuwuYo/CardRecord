package com.shuaji.cards.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shuaji.cards.data.CardNetworkProvider

internal data class RingLayout(
    val diameter: Dp,
    val centerX: Dp,
    val centerY: Dp,
) {
    val radius: Dp get() = diameter / 2
}

internal data class CardNetworkVisualLayout(
    val badgeWidth: Dp,
    val badgeInset: Dp,
    val contentEndPadding: Dp,
    val watermarkWidth: Dp,
    val watermarkHeight: Dp,
    val watermarkRight: Dp,
    val watermarkTop: Dp,
    val largeRing: RingLayout,
    val smallRing: RingLayout,
)

internal fun resolveCardNetworkVisualLayout(cardWidth: Dp): CardNetworkVisualLayout {
    val badgeWidth = (cardWidth * 0.22f).coerceIn(36.dp, 64.dp)
    val badgeInset = (cardWidth * 0.025f).coerceIn(6.dp, 10.dp)
    val largeDiameter = cardWidth * 48f / 100f
    val smallDiameter = cardWidth * 34f / 100f
    return CardNetworkVisualLayout(
        badgeWidth = badgeWidth,
        badgeInset = badgeInset,
        contentEndPadding = badgeWidth + badgeInset + 12.dp,
        watermarkWidth = cardWidth * 0.35f,
        watermarkHeight = cardWidth * 0.24f,
        watermarkRight = cardWidth * 0.02f,
        watermarkTop = cardWidth * 0.02f,
        largeRing = RingLayout(largeDiameter, cardWidth * 0.85f, -cardWidth * 0.05f),
        smallRing = RingLayout(smallDiameter, cardWidth * 0.66f, cardWidth * 0.16f),
    )
}

@Composable
internal fun BoxScope.ProviderNetworkDecoration(
    network: CardNetworkProvider,
    layout: CardNetworkVisualLayout,
) {
    NetworkRing(layout.largeRing)
    NetworkRing(layout.smallRing)
    Image(
        painter = painterResource(network.markRes),
        contentDescription = null,
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = layout.watermarkTop, end = layout.watermarkRight)
                .size(width = layout.watermarkWidth, height = layout.watermarkHeight),
        colorFilter = ColorFilter.tint(Color.White),
        contentScale = ContentScale.Fit,
        alpha = 0.16f,
    )
}

@Composable
private fun BoxScope.NetworkRing(layout: RingLayout) {
    Box(
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = layout.centerX - layout.radius,
                    y = layout.centerY - layout.radius,
                ).size(layout.diameter)
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
    )
}

@Composable
internal fun BoxScope.NetworkCornerBadge(
    network: CardNetworkProvider,
    layout: CardNetworkVisualLayout,
) {
    Box(
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = layout.badgeInset, bottom = layout.badgeInset)
                .size(width = layout.badgeWidth, height = layout.badgeWidth * (2f / 3f))
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(network.markRes),
            contentDescription = stringResource(network.displayNameRes),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
