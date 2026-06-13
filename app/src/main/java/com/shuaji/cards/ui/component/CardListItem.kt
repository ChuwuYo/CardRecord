package com.shuaji.cards.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shuaji.cards.data.local.CardEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 列表单卡项。
 *
 * 布局（自上而下）：
 *  1. 卡面（由 [CardVisual] 绘制）
 *  2. 信息区：进度条 + 笔数 + 有效期 + 下次结算 + 操作行
 *
 * 整张卡片使用一个 [Surface] 容器，提供明确的层级区分。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardListItem(
    card: CardEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIncrement: () -> Unit,
    onDetail: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
    val isDone = card.currentCount >= card.requiredCount
    val isPortrait = card.cardOrientation == "PORTRAIT"

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. 卡面
            // - 列表模式（compact=false）：横版卡 88% 宽居中、竖版卡 60% 宽居中
            //   给两侧留点呼吸空间，整体不显挤
            // - 网格模式（compact=true）：撑满 cell 宽
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val cardWidthFraction =
                    when {
                        compact -> 1f
                        isPortrait -> 0.6f
                        else -> 0.88f
                    }
                CardVisual(
                    card = card,
                    modifier = Modifier.fillMaxWidth(cardWidthFraction),
                )
            }

            // 2. 信息区
            if (compact) {
                CompactInfoArea(
                    card = card,
                    animatedProgress = animatedProgress,
                    isDone = isDone,
                )
            } else {
                FullInfoArea(
                    card = card,
                    animatedProgress = animatedProgress,
                    isDone = isDone,
                    onIncrement = onIncrement,
                    onDetail = onDetail,
                )
            }
        }
    }
}

@Composable
private fun CompactInfoArea(
    card: CardEntity,
    animatedProgress: Float,
    isDone: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${card.currentCount} / ${card.requiredCount} 笔",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
            color = if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
        )
        val dateText =
            card.nextDueDateMillis?.let { "下次结算 ${formatDate(it)}" }
                ?: card.validUntilMillis?.let { "有效期至 ${formatDate(it)}" }
        if (dateText != null) {
            Text(
                dateText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FullInfoArea(
    card: CardEntity,
    animatedProgress: Float,
    isDone: Boolean,
    onIncrement: () -> Unit,
    onDetail: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 进度块
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${card.currentCount} / ${card.requiredCount}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "笔",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (isDone) "已达标" else "还需 ${card.requiredCount - card.currentCount} 笔",
                style = MaterialTheme.typography.labelMedium,
                color = if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
            color = if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
        )

        // 日期行
        if (card.validUntilMillis != null || card.nextDueDateMillis != null) {
            Spacer(Modifier.height(2.dp))
            if (card.validUntilMillis != null) {
                DateRow(icon = Icons.Default.CreditCard, label = "有效期", value = formatDate(card.validUntilMillis))
            }
            if (card.nextDueDateMillis != null) {
                DateRow(icon = Icons.Default.Event, label = "下次结算", value = formatDate(card.nextDueDateMillis))
            }
        }

        // 操作行
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "长按可删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onIncrement) {
                    Text("+1 笔", fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onDetail) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("详情")
                }
            }
        }
    }
}

@Composable
private fun DateRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "$label  ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatDate(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(millis))
}
