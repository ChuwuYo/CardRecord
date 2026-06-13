package com.example.creditcardtracker.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
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

/** 横版卡片宽高比（ISO/IEC 7810 ID-1） */
private const val LANDSCAPE_RATIO = 1.586f

/** 竖版卡片宽高比 */
private const val PORTRAIT_HEIGHT_RATIO = 1.6f

/** 竖版宽度占父容器比例 */
private const val PORTRAIT_WIDTH_FRACTION = 0.6f

/**
 * 信用卡视觉组件：渐变背景 + 进度条 + 卡面图片 + 装饰斜条纹。
 *
 * 横版 LANDSCAPE：宽高比 ≈ 1.586 : 1（标准 ISO/IEC 7810 ID-1 信用卡）
 * 竖版 PORTRAIT：宽高比 ≈ 0.625 : 1（运通 / 大来卡），居中显示
 */
@Composable
fun CreditCardVisual(
    card: CreditCardEntity,
    modifier: Modifier = Modifier,
) {
    val progress =
        if (card.requiredCount > 0) {
            card.currentCount.toFloat() / card.requiredCount.toFloat()
        } else {
            0f
        }
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "progress",
    )

    val base = Color(card.colorArgb)
    val gradient =
        Brush.linearGradient(
            colors =
                listOf(
                    base.copy(alpha = 1f),
                    base.copy(alpha = 0.78f),
                    base.copy(alpha = 0.95f),
                ),
        )

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
                    gradient = gradient,
                    network = network,
                    sourceType = sourceType,
                )
            CardOrientation.PORTRAIT ->
                PortraitCardBody(
                    card = card,
                    gradient = gradient,
                    network = network,
                    sourceType = sourceType,
                )
        }
    }

    Spacer(Modifier.height(12.dp))

    // 进度：x / y
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${card.currentCount} / ${card.requiredCount}",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Black,
        )
        Text(
            text =
                if (card.currentCount >= card.requiredCount) {
                    "已达标"
                } else {
                    "还需 ${card.requiredCount - card.currentCount} 笔"
                },
            style = MaterialTheme.typography.labelMedium,
            color =
                if (card.currentCount >= card.requiredCount) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier =
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(MaterialTheme.shapes.extraSmall),
        color =
            if (card.currentCount >= card.requiredCount) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.primary
            },
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
    )

    if (card.nextDueDateMillis != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "下次年费结算：" + formatDate(card.nextDueDateMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (card.validUntilMillis != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "卡片有效至：" + formatDate(card.validUntilMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 横版 / 竖版 body ──────────────────────────────────────────────

@Composable
private fun LandscapeCardBody(
    card: CreditCardEntity,
    gradient: Brush,
    network: CardNetworkProvider?,
    sourceType: ImageSourceType,
) {
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 168.dp)
                .clip(MaterialTheme.shapes.extraLarge),
    ) {
        val height: Dp = (maxWidth / LANDSCAPE_RATIO).coerceAtLeast(168.dp)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(gradient),
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
                        .padding(20.dp),
                card = card,
                network = network,
            )
        }
    }
}

@Composable
private fun PortraitCardBody(
    card: CreditCardEntity,
    gradient: Brush,
    network: CardNetworkProvider?,
    sourceType: ImageSourceType,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val width: Dp =
            (maxWidth * PORTRAIT_WIDTH_FRACTION)
                .coerceAtMost(240.dp)
                .coerceAtLeast(160.dp)
        val height: Dp = (width * PORTRAIT_HEIGHT_RATIO).coerceAtMost(380.dp)
        Box(
            modifier =
                Modifier
                    .width(width)
                    .height(height)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(gradient),
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
            )
        }
    }
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

// ── 装饰 ──────────────────────────────────────────────────────────

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

// ── 卡面文字 ──────────────────────────────────────────────────────

@Composable
private fun CardContent(
    modifier: Modifier,
    card: CreditCardEntity,
    network: CardNetworkProvider?,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CreditCard,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = card.bank.ifBlank { "—" },
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (network != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier =
                        Modifier
                            .background(
                                Color.White.copy(alpha = 0.18f),
                                MaterialTheme.shapes.extraSmall,
                            ).padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = network.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = card.name.ifBlank { "未命名卡片" },
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.cardNumberMasked.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = card.cardNumberMasked,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDate(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(millis))
}
