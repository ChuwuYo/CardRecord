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
import com.shuaji.cards.data.local.cardOrientationEnum
import com.shuaji.cards.data.local.imageSourceTypeEnum

/**
 * 竖版宽度占父容器比例。
 */
private const val PORTRAIT_WIDTH_FRACTION = 0.6f

/**
 * 卡面最小可视高度（避免在极窄容器下被压成扁条）。
 */
private const val CARD_MIN_HEIGHT_DP = 96f

/** 卡面文字统一使用的暗色投影；银行图标复用其颜色绘制同形底层，不改变上传图片像素。 */
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
    userImageModel: String?,
    modifier: Modifier = Modifier,
    showNumber: Boolean = true,
    contentLayout: CardVisualContentLayout = CardVisualContentLayout.STANDARD,
) {
    val network = CardNetworkProvider.fromKey(card.imageProviderKey)
    val sourceType = card.imageSourceTypeEnum
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

/** 卡面文字布局。双列列表必须显式选择紧凑布局，不能再由卡宽猜测调用场景。 */
enum class CardVisualContentLayout {
    STANDARD,
    COMPACT,
}

// ── 横版 / 竖版 body ──────────────────────────────────────────────

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
        // 严格按 ISO 7810 ID-1 比例，比例单一来源是 CardOrientation.aspectRatio
        val aspectHeight: Dp =
            (maxWidth / card.cardOrientationEnum.aspectRatio).coerceAtLeast(CARD_MIN_HEIGHT_DP.dp)
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
        // 双列仍优先保持标准比例；字体放大后则扩展高度，不能为了卡片比例裁掉可访问文本。
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
        // 竖版：width = parent * 0.6，限制在 100..180dp 之间
        val width: Dp =
            (maxWidth * PORTRAIT_WIDTH_FRACTION)
                .coerceIn(100.dp, 180.dp)
        // aspectRatio 始终表示宽 / 高；竖版必须用宽度除以比例，不能把 0.631 再当成高 / 宽相乘。
        val height: Dp = resolveCardHeight(width, card.cardOrientationEnum).coerceAtMost(280.dp)
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
                    // 与编辑预览一致：用户选择的整张图片都保留，留白由卡片底色承接。
                    contentScale = ContentScale.Fit,
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

internal data class CardContentPlacement(
    val start: Dp,
    val top: Dp,
    val bottom: Dp,
)

/** 紧凑性由列表容器决定；卡面朝向只负责几何比例，不能反向覆盖排版密度。 */
internal fun isCompactCardContent(contentLayout: CardVisualContentLayout): Boolean = contentLayout == CardVisualContentLayout.COMPACT

/** 所有朝向统一使用 `宽 / 高` 比例，避免竖版在不同调用点出现互为倒数的解释。 */
internal fun resolveCardHeight(
    width: Dp,
    orientation: CardOrientation,
): Dp = width / orientation.aspectRatio

/** 双列卡文字区的真实最小高度；行高已包含系统 fontScale。 */
internal fun resolveCompactCardMinimumHeight(
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

internal fun resolveCardContentPlacement(
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

internal data class CardContentEndPaddings(
    val bankRow: Dp,
    val nameRow: Dp,
    val numberRow: Dp,
)

internal fun resolveCardContentEndPaddings(
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

// ── 卡面文字内容 ──────────────────────────────────────────────────

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
internal fun BankLabel(
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
        // 矢量图标没有文字的 Shadow 能力；叠一层同字形暗色偏移，避免浅色卡面上消失。
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
