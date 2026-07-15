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
import com.shuaji.cards.data.local.cardOrientationEnum

/**
 * 竖版宽度占父容器比例。
 */
private const val PORTRAIT_WIDTH_FRACTION = 0.6f

/**
 * 卡面最小可视高度（避免在极窄容器下被压成扁条）。
 */
private const val CARD_MIN_HEIGHT_DP = 96f

/** 只作用于文字本身，确保浅色用户图片上可读，不改变上传图片像素。 */
private val CardTextShadow =
    Shadow(
        color = Color.Black.copy(alpha = 0.65f),
        offset = Offset(0f, 1f),
        blurRadius = 3f,
    )

/**
 * 卡片视觉组件：底色渐变 + 样式背景层 + 卡面图片与文字。
 *
 * 简化为「只画卡面」，不再附带进度条 / 笔数 / 日期——
 * 那些由列表项 [CardListItem] 在卡外侧的信息区展示。
 *
 * 横版 LANDSCAPE：宽高比 ≈ 1.586 : 1
 * 竖版 PORTRAIT：高宽比 ≈ 1.586 : 1，宽度自动取父级 60% 居中
 *
 * 三种卡面来源：
 * - USER：用户上传图片
 * - PROVIDER：卡组织预设装饰
 * - NONE：无图片层
 *
 * 三种来源都使用 [CardEntity.colorArgb] 作为底色。
 */
@Composable
fun CardVisual(
    card: CardEntity,
    modifier: Modifier = Modifier,
    showNumber: Boolean = true,
    showBank: Boolean = true,
    showName: Boolean = true,
) {
    val network = CardNetworkProvider.fromKey(card.imageProviderKey)
    val sourceType =
        runCatching { ImageSourceType.valueOf(card.imageSourceType) }
            .getOrDefault(ImageSourceType.NONE)
    val orientation = card.cardOrientationEnum

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        // 竖版卡面只占父级中间一段；外层 Card 若投影会形成整行阴影，改由实际卡面盒子投影。
        elevation = CardDefaults.cardElevation(defaultElevation = if (orientation == CardOrientation.PORTRAIT) 0.dp else 4.dp),
    ) {
        when (orientation) {
            CardOrientation.LANDSCAPE ->
                LandscapeCardBody(
                    card = card,
                    network = network,
                    sourceType = sourceType,
                    showNumber = showNumber,
                    showBank = showBank,
                    showName = showName,
                )
            CardOrientation.PORTRAIT ->
                PortraitCardBody(
                    card = card,
                    network = network,
                    sourceType = sourceType,
                    showNumber = showNumber,
                    showBank = showBank,
                    showName = showName,
                )
        }
    }
}

// ── 横版 / 竖版 body ──────────────────────────────────────────────

@Composable
private fun LandscapeCardBody(
    card: CardEntity,
    network: CardNetworkProvider?,
    sourceType: ImageSourceType,
    showNumber: Boolean,
    showBank: Boolean,
    showName: Boolean,
) {
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CARD_MIN_HEIGHT_DP.dp)
                .clip(MaterialTheme.shapes.medium),
    ) {
        // 严格按 ISO 7810 ID-1 比例，比例单一来源是 CardOrientation.aspectRatio
        val height: Dp =
            (maxWidth / card.cardOrientationEnum.aspectRatio).coerceAtLeast(CARD_MIN_HEIGHT_DP.dp)
        val networkLayout = resolveCardNetworkVisualLayout(maxWidth)
        val textLift =
            resolveCardTextLift(
                orientation = CardOrientation.LANDSCAPE,
                sourceType = sourceType,
                networkPresent = network != null,
                cardWidth = maxWidth,
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
                card = card,
                sourceType = sourceType,
            )
            if (shouldShowProviderDecoration(sourceType, network != null)) {
                ProviderNetworkDecoration(
                    network = checkNotNull(network),
                    layout = networkLayout,
                )
            }
            CardContent(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .offset(y = -textLift)
                        .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                card = card,
                contentEndPadding =
                    if (shouldShowNetworkBadge(sourceType, network != null)) {
                        networkLayout.contentEndPadding
                    } else {
                        16.dp
                    },
                showNumber = showNumber,
                showBank = showBank,
                showName = showName,
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
    network: CardNetworkProvider?,
    sourceType: ImageSourceType,
    showNumber: Boolean,
    showBank: Boolean,
    showName: Boolean,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // 竖版：width = parent * 0.6，限制在 100..180dp 之间
        val width: Dp =
            (maxWidth * PORTRAIT_WIDTH_FRACTION)
                .coerceIn(100.dp, 180.dp)
        // 高度严格按 1.586:1 比例，比例单一来源是 CardOrientation.aspectRatio
        val height: Dp = (width * card.cardOrientationEnum.aspectRatio).coerceAtMost(280.dp)
        val networkLayout = resolveCardNetworkVisualLayout(width)
        val textLift =
            resolveCardTextLift(
                orientation = CardOrientation.PORTRAIT,
                sourceType = sourceType,
                networkPresent = network != null,
                cardWidth = width,
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
                card = card,
                sourceType = sourceType,
            )
            if (shouldShowProviderDecoration(sourceType, network != null)) {
                ProviderNetworkDecoration(
                    network = checkNotNull(network),
                    layout = networkLayout,
                )
            }
            CardContent(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .offset(y = -textLift)
                        .padding(start = 14.dp, top = 14.dp, bottom = 14.dp),
                card = card,
                contentEndPadding =
                    if (shouldShowNetworkBadge(sourceType, network != null)) {
                        networkLayout.contentEndPadding
                    } else {
                        14.dp
                    },
                showNumber = showNumber,
                showBank = showBank,
                showName = showName,
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

// ── 卡面背景（核心：由保存的主题色生成不透明渐变） ──────────────────

/** 卡面来源只决定图片层；底色始终以用户保存的主题色为准。 */
@Composable
private fun cardSurfaceBrush(card: CardEntity): Brush = Brush.linearGradient(colors = resolveCardSurfaceColors(card.colorArgb))

internal fun resolveCardSurfaceColors(colorArgb: Int): List<Color> {
    val base = Color(colorArgb).copy(alpha = 1f)
    return listOf(
        base,
        lerp(base, Color.White, 0.06f).copy(alpha = 1f),
        lerp(base, Color.Black, 0.04f).copy(alpha = 1f),
    )
}

// ── 卡面图片层 ────────────────────────────────────────────────────

@Composable
private fun CardImageLayer(
    modifier: Modifier,
    card: CardEntity,
    sourceType: ImageSourceType,
) {
    when (sourceType) {
        ImageSourceType.USER -> {
            if (!card.imageUri.isNullOrBlank()) {
                AsyncImage(
                    model = card.imageUri,
                    contentDescription = stringResource(R.string.card_image_content_description),
                    modifier = modifier.clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        ImageSourceType.PROVIDER -> Unit
        ImageSourceType.NONE -> Unit
    }
}

internal fun shouldShowProviderDecoration(
    sourceType: ImageSourceType,
    networkPresent: Boolean,
): Boolean = sourceType == ImageSourceType.PROVIDER && networkPresent

internal fun shouldShowNetworkBadge(
    sourceType: ImageSourceType,
    networkPresent: Boolean,
): Boolean = sourceType != ImageSourceType.USER && networkPresent

internal fun resolveCardTextLift(
    orientation: CardOrientation,
    sourceType: ImageSourceType,
    networkPresent: Boolean,
    cardWidth: Dp,
): Dp =
    if (
        orientation == CardOrientation.LANDSCAPE &&
        shouldShowNetworkBadge(sourceType, networkPresent) &&
        cardWidth <= 180.dp
    ) {
        8.dp
    } else {
        0.dp
    }

// ── 卡面文字内容 ──────────────────────────────────────────────────

@Composable
private fun CardContent(
    modifier: Modifier,
    card: CardEntity,
    contentEndPadding: Dp,
    showNumber: Boolean,
    showBank: Boolean,
    showName: Boolean,
) {
    Column(modifier = modifier.padding(end = contentEndPadding)) {
        if (showBank) {
            BankLabel(card = card, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        if (showName) {
            Text(
                text = card.name.ifBlank { stringResource(R.string.card_default_name) },
                style = MaterialTheme.typography.titleLarge.copy(shadow = CardTextShadow),
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showNumber && card.cardNumberMasked.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                card.cardNumberMasked,
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
        Icon(
            imageVector = Icons.Default.CreditCard,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(14.dp),
        )
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
