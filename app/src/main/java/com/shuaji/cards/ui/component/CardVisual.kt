package com.shuaji.cards.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.shuaji.cards.R
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.ImageSourceType

private const val PORTRAIT_WIDTH_FRACTION = 0.6f

private const val CARD_MIN_HEIGHT_DP = 96f

private val CardTextShadow =
    Shadow(
        color = Color.Black.copy(alpha = 0.65f),
        offset = Offset(0f, 1f),
        blurRadius = 3f,
    )

@Composable
fun CardVisual(
    card: CardEntity,
    userImageModel: String?,
    modifier: Modifier = Modifier,
    showNumber: Boolean = true,
    contentLayout: CardVisualContentLayout = CardVisualContentLayout.STANDARD,
) {
    val network = CardNetworkProvider.fromKey(card.imageProviderKey)
    val sourceType = card.imageSourceType
    val orientation = card.cardOrientation

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = if (orientation == CardOrientation.PORTRAIT) 0.dp else 4.dp),
    ) {
        when (orientation) {
            CardOrientation.LANDSCAPE ->
                LandscapeCardBody(
                    card = card,
                    userImageModel = userImageModel,
                    network = network,
                    sourceType = sourceType,
                    showNumber = showNumber,
                    contentLayout = contentLayout,
                )
            CardOrientation.PORTRAIT ->
                PortraitCardBody(
                    card = card,
                    userImageModel = userImageModel,
                    network = network,
                    sourceType = sourceType,
                    showNumber = showNumber,
                    contentLayout = contentLayout,
                )
        }
    }
}

enum class CardVisualContentLayout {
    STANDARD,
    COMPACT,
}

@Composable
private fun LandscapeCardBody(
    card: CardEntity,
    userImageModel: String?,
    network: CardNetworkProvider?,
    sourceType: ImageSourceType,
    showNumber: Boolean,
    contentLayout: CardVisualContentLayout,
) {
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CARD_MIN_HEIGHT_DP.dp)
                .clip(MaterialTheme.shapes.medium),
    ) {
        val aspectHeight: Dp =
            (maxWidth / card.cardOrientation.aspectRatio).coerceAtLeast(CARD_MIN_HEIGHT_DP.dp)
        val compact = isCompactCardContent(contentLayout)
        val networkLayout =
            resolveCardNetworkVisualLayout(
                cardWidth = maxWidth,
                providerDecorationScale = if (compact) COMPACT_PROVIDER_DECORATION_SCALE else 1f,
            )
        val contentPlacement =
            resolveCardContentPlacement(
                contentLayout = contentLayout,
                networkLayout = networkLayout,
                badgeVisible = shouldShowNetworkBadge(sourceType, network != null),
                defaultPadding = 16.dp,
            )
        val compactMinimumHeight =
            if (compact) {
                val density = LocalDensity.current
                resolveCompactCardMinimumHeight(
                    bankLineHeight =
                        with(density) {
                            MaterialTheme.typography.labelMedium.lineHeight
                                .toDp()
                        },
                    nameLineHeight =
                        with(density) {
                            MaterialTheme.typography.titleMedium.lineHeight
                                .toDp()
                        },
                    numberLineHeight =
                        if (showNumber && card.cardNumberMasked.isNotBlank()) {
                            with(density) {
                                MaterialTheme.typography.titleMedium.lineHeight
                                    .toDp()
                            }
                        } else {
                            null
                        },
                    contentPlacement = contentPlacement,
                )
            } else {
                0.dp
            }
        val height = maxOf(aspectHeight, compactMinimumHeight)
        val contentEndPaddings =
            resolveCardContentEndPaddings(
                sourceType = sourceType,
                networkPresent = network != null,
                networkLayout = networkLayout,
                contentLayout = contentLayout,
                defaultPadding = 16.dp,
            )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(cardSurfaceBrush(card)),
        ) {
            CardImageLayer(
                modifier = Modifier.fillMaxSize(),
                userImageModel = userImageModel,
                sourceType = sourceType,
            )
            if (shouldShowProviderDecoration(sourceType, network != null)) {
                ProviderNetworkDecoration(
                    network = checkNotNull(network),
                    layout = networkLayout.providerDecoration,
                )
            }
            CardContent(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(
                            start = contentPlacement.start,
                            top = contentPlacement.top,
                            bottom = contentPlacement.bottom,
                        ),
                card = card,
                contentEndPaddings = contentEndPaddings,
                showNumber = showNumber,
                compactName = compact,
            )
            if (shouldShowNetworkBadge(sourceType, network != null)) {
                NetworkCornerBadge(
                    network = checkNotNull(network),
                    layout = networkLayout,
                )
            }
        }
    }
}

@Composable
private fun PortraitCardBody(
    card: CardEntity,
    userImageModel: String?,
    network: CardNetworkProvider?,
    sourceType: ImageSourceType,
    showNumber: Boolean,
    contentLayout: CardVisualContentLayout,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val width: Dp =
            (maxWidth * PORTRAIT_WIDTH_FRACTION)
                .coerceIn(100.dp, 180.dp)
        val height: Dp = resolveCardHeight(width, card.cardOrientation).coerceAtMost(280.dp)
        val compact = isCompactCardContent(contentLayout)
        val networkLayout =
            resolveCardNetworkVisualLayout(
                cardWidth = width,
                providerDecorationScale = if (compact) COMPACT_PROVIDER_DECORATION_SCALE else 1f,
            )
        val contentPlacement =
            resolveCardContentPlacement(
                contentLayout = contentLayout,
                networkLayout = networkLayout,
                badgeVisible = shouldShowNetworkBadge(sourceType, network != null),
                defaultPadding = 14.dp,
            )
        val contentEndPaddings =
            resolveCardContentEndPaddings(
                sourceType = sourceType,
                networkPresent = network != null,
                networkLayout = networkLayout,
                contentLayout = contentLayout,
                defaultPadding = 14.dp,
            )
        Box(
            modifier =
                Modifier
                    .width(width)
                    .height(height)
                    .shadow(4.dp, MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium)
                    .background(cardSurfaceBrush(card)),
        ) {
            CardImageLayer(
                modifier = Modifier.fillMaxSize(),
                userImageModel = userImageModel,
                sourceType = sourceType,
            )
            if (shouldShowProviderDecoration(sourceType, network != null)) {
                ProviderNetworkDecoration(
                    network = checkNotNull(network),
                    layout = networkLayout.providerDecoration,
                )
            }
            CardContent(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(
                            start = contentPlacement.start,
                            top = contentPlacement.top,
                            bottom = contentPlacement.bottom,
                        ),
                card = card,
                contentEndPaddings = contentEndPaddings,
                showNumber = showNumber,
                compactName = compact,
            )
            if (shouldShowNetworkBadge(sourceType, network != null)) {
                NetworkCornerBadge(
                    network = checkNotNull(network),
                    layout = networkLayout,
                )
            }
        }
    }
}

@Composable
private fun cardSurfaceBrush(card: CardEntity): Brush = Brush.linearGradient(colors = resolveCardSurfaceColors(card.colorArgb))

private fun resolveCardSurfaceColors(colorArgb: Int): List<Color> {
    val base = Color(colorArgb).copy(alpha = 1f)
    return listOf(
        base,
        lerp(base, Color.White, 0.06f).copy(alpha = 1f),
        lerp(base, Color.Black, 0.04f).copy(alpha = 1f),
    )
}

@Composable
private fun CardImageLayer(
    modifier: Modifier,
    userImageModel: String?,
    sourceType: ImageSourceType,
) {
    when (sourceType) {
        ImageSourceType.USER -> {
            if (!userImageModel.isNullOrBlank()) {
                AsyncImage(
                    model = userImageModel,
                    contentDescription = stringResource(R.string.card_image_content_description),
                    modifier = modifier.clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        ImageSourceType.PROVIDER -> Unit
        ImageSourceType.NONE -> Unit
    }
}

private fun shouldShowProviderDecoration(
    sourceType: ImageSourceType,
    networkPresent: Boolean,
): Boolean = sourceType == ImageSourceType.PROVIDER && networkPresent

private fun shouldShowNetworkBadge(
    sourceType: ImageSourceType,
    networkPresent: Boolean,
): Boolean = sourceType != ImageSourceType.USER && networkPresent

private data class CardContentPlacement(
    val start: Dp,
    val top: Dp,
    val bottom: Dp,
)

private fun isCompactCardContent(contentLayout: CardVisualContentLayout): Boolean = contentLayout == CardVisualContentLayout.COMPACT

private fun resolveCardHeight(
    width: Dp,
    orientation: CardOrientation,
): Dp = width / orientation.aspectRatio

private fun resolveCompactCardMinimumHeight(
    bankLineHeight: Dp,
    nameLineHeight: Dp,
    numberLineHeight: Dp?,
    contentPlacement: CardContentPlacement,
): Dp =
    contentPlacement.top +
        maxOf(bankLineHeight, 14.dp) +
        4.dp +
        nameLineHeight +
        (numberLineHeight?.let { 2.dp + it } ?: 0.dp) +
        contentPlacement.bottom

private fun resolveCardContentPlacement(
    contentLayout: CardVisualContentLayout,
    networkLayout: CardNetworkVisualLayout,
    badgeVisible: Boolean,
    defaultPadding: Dp,
): CardContentPlacement =
    if (isCompactCardContent(contentLayout)) {
        val compactInset = networkLayout.badgeInset
        CardContentPlacement(
            start = compactInset,
            top = compactInset,
            bottom =
                if (badgeVisible) {
                    compactInset + networkLayout.badgeHeight + 4.dp
                } else {
                    compactInset
                },
        )
    } else {
        CardContentPlacement(
            start = defaultPadding,
            top = defaultPadding,
            bottom = defaultPadding,
        )
    }

private data class CardContentEndPaddings(
    val bankRow: Dp,
    val nameRow: Dp,
    val numberRow: Dp,
)

private fun resolveCardContentEndPaddings(
    sourceType: ImageSourceType,
    networkPresent: Boolean,
    networkLayout: CardNetworkVisualLayout,
    contentLayout: CardVisualContentLayout,
    defaultPadding: Dp,
): CardContentEndPaddings {
    val badgePadding =
        if (shouldShowNetworkBadge(sourceType, networkPresent)) {
            networkLayout.contentEndPadding
        } else {
            defaultPadding
        }
    val compact = isCompactCardContent(contentLayout)
    val compactEdgePadding = networkLayout.badgeInset
    return CardContentEndPaddings(
        bankRow =
            if (compact && shouldShowProviderDecoration(sourceType, networkPresent)) {
                networkLayout.providerDecoration.motifEndPadding
            } else if (compact) {
                compactEdgePadding
            } else {
                badgePadding
            },
        nameRow = if (compact) compactEdgePadding else badgePadding,
        numberRow = badgePadding,
    )
}

@Composable
private fun CardContent(
    modifier: Modifier,
    card: CardEntity,
    contentEndPaddings: CardContentEndPaddings,
    showNumber: Boolean,
    compactName: Boolean,
) {
    Column(modifier = modifier) {
        BankLabel(
            card = card,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = contentEndPaddings.bankRow),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = card.name.ifBlank { stringResource(R.string.card_default_name) },
            modifier = Modifier.padding(end = contentEndPaddings.nameRow),
            style =
                if (compactName) {
                    MaterialTheme.typography.titleMedium.copy(shadow = CardTextShadow)
                } else {
                    MaterialTheme.typography.titleLarge.copy(shadow = CardTextShadow)
                },
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showNumber && card.cardNumberMasked.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                card.cardNumberMasked,
                modifier = Modifier.padding(end = contentEndPaddings.numberRow),
                style = MaterialTheme.typography.titleMedium.copy(shadow = CardTextShadow),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BankLabel(
    card: CardEntity,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BankIcon()
        Spacer(Modifier.width(6.dp))
        Text(
            text = card.bank.ifBlank { stringResource(R.string.card_default_bank) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium.copy(shadow = CardTextShadow),
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BankIcon() {
    Box(
        modifier = Modifier.size(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.CreditCard,
            contentDescription = null,
            tint = CardTextShadow.color,
            modifier =
                Modifier
                    .size(14.dp)
                    .offset(y = 0.75.dp),
        )
        Icon(
            imageVector = Icons.Default.CreditCard,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(14.dp),
        )
    }
}
