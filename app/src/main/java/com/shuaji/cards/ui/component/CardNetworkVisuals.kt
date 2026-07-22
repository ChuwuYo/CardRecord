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

internal const val COMPACT_PROVIDER_DECORATION_SCALE = 0.46f

private val NetworkDecorationShadowColor = Color.Black.copy(alpha = 0.28f)
private val NetworkDecorationShadowOffset = 0.75.dp
private val NetworkDecorationColor = Color.White.copy(alpha = 0.16f)
private val NetworkBadgeSurfaceColor = Color.White.copy(alpha = 0.14f)
private val NetworkBadgeShape = RoundedCornerShape(8.dp)

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
    val providerDecoration: ProviderDecorationLayout,
) {
    val badgeHeight: Dp get() = badgeWidth * (2f / 3f)
}

internal data class ProviderDecorationLayout(
    val cardWidth: Dp,
    val watermarkWidth: Dp,
    val watermarkHeight: Dp,
    val watermarkRight: Dp,
    val watermarkTop: Dp,
    val largeRing: RingLayout,
    val smallRing: RingLayout,
    val ringStroke: Dp,
) {
    /** 从装饰组最左侧派生文字安全区，不能只按水印宽度忽略向左伸出的圆环。 */
    val motifEndPadding: Dp
        get() {
            val leftEdge =
                minOf(
                    cardWidth - watermarkRight - watermarkWidth,
                    largeRing.centerX - largeRing.radius,
                    smallRing.centerX - smallRing.radius,
                )
            return cardWidth - leftEdge + 4.dp
        }
}

/** 以卡面右上角为锚统一缩放水印、双环及描边，保持装饰组内部关系不变。 */
internal fun ProviderDecorationLayout.scaledFromTopEnd(scale: Float): ProviderDecorationLayout =
    copy(
        watermarkWidth = watermarkWidth * scale,
        watermarkHeight = watermarkHeight * scale,
        watermarkRight = watermarkRight * scale,
        watermarkTop = watermarkTop * scale,
        largeRing = largeRing.scaledFromTopEnd(cardWidth, scale),
        smallRing = smallRing.scaledFromTopEnd(cardWidth, scale),
        ringStroke = ringStroke * scale,
    )

private fun RingLayout.scaledFromTopEnd(
    cardWidth: Dp,
    scale: Float,
): RingLayout =
    RingLayout(
        diameter = diameter * scale,
        centerX = cardWidth + (centerX - cardWidth) * scale,
        centerY = centerY * scale,
    )

internal fun resolveCardNetworkVisualLayout(
    cardWidth: Dp,
    providerDecorationScale: Float = 1f,
): CardNetworkVisualLayout {
    val badgeWidth = (cardWidth * 0.22f).coerceIn(36.dp, 64.dp)
    val badgeInset = (cardWidth * 0.025f).coerceIn(6.dp, 10.dp)
    val largeDiameter = cardWidth * 48f / 100f
    val smallDiameter = cardWidth * 34f / 100f
    val providerDecoration =
        ProviderDecorationLayout(
            cardWidth = cardWidth,
            watermarkWidth = cardWidth * 0.35f,
            watermarkHeight = cardWidth * 0.24f,
            watermarkRight = cardWidth * 0.02f,
            watermarkTop = cardWidth * 0.02f,
            largeRing = RingLayout(largeDiameter, cardWidth * 0.85f, -cardWidth * 0.05f),
            smallRing = RingLayout(smallDiameter, cardWidth * 0.66f, cardWidth * 0.16f),
            ringStroke = 1.dp,
        )
    return CardNetworkVisualLayout(
        badgeWidth = badgeWidth,
        badgeInset = badgeInset,
        contentEndPadding = badgeWidth + badgeInset + 12.dp,
        providerDecoration =
            if (providerDecorationScale == 1f) {
                providerDecoration
            } else {
                providerDecoration.scaledFromTopEnd(providerDecorationScale)
            },
    )
}

@Composable
internal fun BoxScope.ProviderNetworkDecoration(
    network: CardNetworkProvider,
    layout: ProviderDecorationLayout,
) {
    NetworkRing(layout.largeRing, layout.ringStroke)
    NetworkRing(layout.smallRing, layout.ringStroke)
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = layout.watermarkTop, end = layout.watermarkRight)
                .size(width = layout.watermarkWidth, height = layout.watermarkHeight),
    ) {
        // 白色半透明水印在浅色卡面会完全消失；同形暗色底层只补轮廓，不改变品牌图形。
        Image(
            painter = painterResource(network.markRes),
            contentDescription = null,
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset(y = NetworkDecorationShadowOffset),
            colorFilter = ColorFilter.tint(NetworkDecorationShadowColor),
            contentScale = ContentScale.Fit,
        )
        Image(
            painter = painterResource(network.markRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(NetworkDecorationColor),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun BoxScope.NetworkRing(
    layout: RingLayout,
    strokeWidth: Dp,
) {
    Box(
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = layout.centerX - layout.radius,
                    y = layout.centerY - layout.radius,
                ).size(layout.diameter),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset(y = NetworkDecorationShadowOffset)
                    .border(strokeWidth, NetworkDecorationShadowColor, CircleShape),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .border(strokeWidth, NetworkDecorationColor, CircleShape),
        )
    }
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
                .size(width = layout.badgeWidth, height = layout.badgeHeight),
    ) {
        // 底板保留原透明度；暗色同形底层提供浅色卡面上的边界与轻微纵深。
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset(y = NetworkDecorationShadowOffset)
                    .background(NetworkDecorationShadowColor, NetworkBadgeShape),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(NetworkBadgeSurfaceColor, NetworkBadgeShape)
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
}
