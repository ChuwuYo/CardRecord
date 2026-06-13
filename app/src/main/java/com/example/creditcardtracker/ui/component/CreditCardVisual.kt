package com.example.creditcardtracker.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.creditcardtracker.data.CardNetworkProvider
import com.example.creditcardtracker.data.local.CardOrientation
import com.example.creditcardtracker.data.local.CreditCardEntity
import com.example.creditcardtracker.data.local.ImageSourceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ISO/IEC 7810 ID-1 标准信用卡比例：85.60 mm × 53.98 mm ⇒ 长宽比 1.586:1。
 * 横版（LANDSCAPE）= 宽度/高度 ≈ 1.586。
 * 竖版（PORTRAIT）= 高度/宽度 ≈ 1.586（同一物理卡片旋转 90°）。
 *
 * 之前 PORTRAIT_RATIO 写成 1.6、且 LandscapeCardBody 用了
 * `heightIn(min = 140.dp)`，导致 grid 模式（cell 宽 ~158dp）下卡片被
 * 强制拉到 158×140 → 几乎成正方形。这里统一改为 1.586f，并把下限
 * 放宽到 96dp 让卡片始终是标准长方形比例。
 */
private const val LANDSCAPE_RATIO = 1.586f
private const val PORTRAIT_RATIO = 1.586f

/** 竖版宽度占父容器比例 */
private const val PORTRAIT_WIDTH_FRACTION = 0.6f

/** 卡面最小可视高度（避免在极窄容器下被压成扁条） */
private const val CARD_MIN_HEIGHT_DP = 96f

/** 卡面演示用淡灰反光色（当无品牌色时使用） */
private val DEFAULT_GREY_BASE = 0xFF8A8E96.toInt()

/**
 * 信用卡视觉组件：渐变 + 反光 + 卡面图片。
 *
 * 简化为「只画卡面」，不再附带进度条 / 笔数 / 日期——
 * 那些由列表项 [CardListItem] 在卡外侧的信息区展示。
 *
 * 横版 LANDSCAPE：宽高比 ≈ 1.586 : 1
 * 竖版 PORTRAIT：高宽比 ≈ 1.6 : 1，宽度自动取父级 60% 居中
 *
 * 三种卡面来源：
 * - USER：用户上传图片
 * - PROVIDER：simple-icons 官方品牌 logo
 * - NONE：质感深灰卡（带反光高光）
 */
@Composable
fun CreditCardVisual(
    card: CreditCardEntity,
    modifier: Modifier = Modifier,
    showNumber: Boolean = true,
    showBank: Boolean = true,
    showName: Boolean = true,
) {
    val network = CardNetworkProvider.fromKey(card.imageProviderKey)
    val sourceType =
        runCatching { ImageSourceType.valueOf(card.imageSourceType) }
            .getOrDefault(ImageSourceType.NONE)
    val orientation =
        runCatching { CardOrientation.valueOf(card.cardOrientation) }
            .getOrDefault(CardOrientation.LANDSCAPE)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
    card: CreditCardEntity,
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
                .clip(MaterialTheme.shapes.extraLarge),
    ) {
        // 严格按 ISO 7810 ID-1 (1.586:1) 计算高度
        val height: Dp = (maxWidth / LANDSCAPE_RATIO).coerceAtLeast(CARD_MIN_HEIGHT_DP.dp)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(cardSurfaceBrush(card, sourceType, network)),
        ) {
            CardImageLayer(
                modifier = Modifier.fillMaxSize(),
                card = card,
                network = network,
                sourceType = sourceType,
            )
            DecorationBlades()
            CardContent(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(16.dp),
                card = card,
                network = network,
                showNumber = showNumber,
                showBank = showBank,
                showName = showName,
            )
        }
    }
}

@Composable
private fun PortraitCardBody(
    card: CreditCardEntity,
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
        // 高度严格按 1.586:1 比例
        val height: Dp = (width * PORTRAIT_RATIO).coerceAtMost(280.dp)
        Box(
            modifier =
                Modifier
                    .width(width)
                    .height(height)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(cardSurfaceBrush(card, sourceType, network)),
        ) {
            CardImageLayer(
                modifier = Modifier.fillMaxSize(),
                card = card,
                network = network,
                sourceType = sourceType,
            )
            DecorationBlades()
            CardContent(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(14.dp),
                card = card,
                network = network,
                showNumber = showNumber,
                showBank = showBank,
                showName = showName,
            )
        }
    }
}

// ── 卡面背景（核心：决定用什么底色 + 反光） ────────────────────────

/**
 * 根据卡面来源返回卡面背景 brush：
 * - USER：用户自定义颜色（来自 colorArgb）
 * - PROVIDER：品牌色渐变
 * - NONE：质感深灰渐变 + 顶部反光（无品牌色）
 */
@Composable
private fun cardSurfaceBrush(
    card: CreditCardEntity,
    sourceType: ImageSourceType,
    network: CardNetworkProvider?,
): Brush {
    val base: Color =
        when (sourceType) {
            ImageSourceType.PROVIDER -> Color(network?.brandColor ?: card.colorArgb)
            ImageSourceType.USER -> Color(card.colorArgb)
            ImageSourceType.NONE -> Color(DEFAULT_GREY_BASE)
        }
    return Brush.linearGradient(
        colors =
            listOf(
                base.copy(alpha = 1f),
                base.copy(alpha = 0.75f),
                base.copy(alpha = 0.95f),
            ),
    )
}

// ── 卡面图片层 ────────────────────────────────────────────────────

@Composable
private fun CardImageLayer(
    modifier: Modifier,
    card: CreditCardEntity,
    network: CardNetworkProvider?,
    sourceType: ImageSourceType,
) {
    when (sourceType) {
        ImageSourceType.USER -> {
            if (!card.imageUri.isNullOrBlank()) {
                AsyncImage(
                    model = card.imageUri,
                    contentDescription = "卡面",
                    modifier = modifier.clip(MaterialTheme.shapes.extraLarge),
                    contentScale = ContentScale.Crop,
                    alpha = 0.35f,
                )
            }
        }
        ImageSourceType.PROVIDER -> {
            if (network != null) {
                Image(
                    painter = painterResource(network.logoRes),
                    contentDescription = network.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = modifier,
                    alpha = 0.45f,
                )
            }
        }
        ImageSourceType.NONE -> Unit
    }
}

// ── 装饰：右上角斜条纹（锋锐风格） ───────────────────────────────

@Composable
private fun BoxScope.DecorationBlades() {
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .size(width = 96.dp, height = 22.dp)
                .rotate(35f)
                .background(Color.White.copy(alpha = 0.18f)),
    )
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 6.dp)
                .size(width = 64.dp, height = 12.dp)
                .rotate(35f)
                .background(Color.White.copy(alpha = 0.10f)),
    )
}

// ── 卡面文字内容 ──────────────────────────────────────────────────

@Composable
private fun CardContent(
    modifier: Modifier,
    card: CreditCardEntity,
    network: CardNetworkProvider?,
    showNumber: Boolean,
    showBank: Boolean,
    showName: Boolean,
) {
    Column(modifier = modifier) {
        if (showBank) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CreditCard,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    card.bank.ifBlank { "—" },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (network != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier =
                            Modifier
                                .background(
                                    Color.White.copy(alpha = 0.18f),
                                    MaterialTheme.shapes.extraSmall,
                                ).padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            network.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (showName) {
            Text(
                card.name.ifBlank { "未命名卡片" },
                style = MaterialTheme.typography.titleLarge,
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
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 仅提取卡号后四位（脱敏显示） */
internal fun lastFourDigits(masked: String): String {
    val digits = masked.filter(Char::isDigit)
    return if (digits.length >= 4) digits.takeLast(4) else digits.ifBlank { "****" }
}

private fun formatDate(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(millis))
}
