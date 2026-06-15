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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shuaji.cards.R
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.cardOrientationEnum
import com.shuaji.cards.ui.screen.CardUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 列表单卡项。
 *
 * 布局（自上而下）：
 *  1. 过期提示条（仅当 [CardUi.isExpired] = true 时显示 —— "凡存在必消费" 兜底）
 *  2. 卡面（由 [CardVisual] 绘制）
 *  3. 信息区：进度条 + 笔数 + 有效期 + 下次结算 + 操作行
 *
 * 整张卡片使用一个 [Surface] 容器，提供明确的层级区分。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardListItem(
    card: CardUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: () -> Unit,
    onDetail: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val progress =
        if (card.card.requiredCount > 0) {
            card.currentCount.toFloat() / card.card.requiredCount.toFloat()
        } else {
            0f
        }
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "progress",
    )
    val isDone = card.currentCount >= card.card.requiredCount
    val isPortrait = card.card.cardOrientationEnum == CardOrientation.PORTRAIT

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
            // 0. 过期提示条 —— 设了 validUntil 就该有提示，否则字段白存在
            if (card.isExpired) {
                ExpiredBanner()
            }

            // 1. 卡面
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
                    card = card.card,
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
                    onSwipe = onSwipe,
                    onDetail = onDetail,
                )
            }
        }
    }
}

/**
 * 已过期红色提示条。复用于 list / grid 两种模式。
 *
 * 红色背景 + 白字 + 感叹号 icon：层级高，眼睛一扫就看见。
 */
@Composable
private fun ExpiredBanner() {
    val expiredColor = MaterialTheme.colorScheme.errorContainer
    val onExpiredColor = MaterialTheme.colorScheme.onErrorContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = expiredColor,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = onExpiredColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.card_status_expired),
                style = MaterialTheme.typography.labelLarge,
                color = onExpiredColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CompactInfoArea(
    card: CardUi,
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
                text = stringResource(R.string.card_count_compact_format, card.currentCount, card.card.requiredCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.card_percent_format, (animatedProgress * 100).toInt()),
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
            card.card.nextDueDateMillis?.let { stringResource(R.string.card_date_next_due, formatDate(it)) }
                ?: card.card.validUntilMillis?.let { stringResource(R.string.card_date_valid_until, formatDate(it)) }
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
    card: CardUi,
    animatedProgress: Float,
    isDone: Boolean,
    onSwipe: () -> Unit,
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
                    text = stringResource(R.string.card_count_format, card.currentCount, card.card.requiredCount),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.card_count_unit),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text =
                    if (isDone) {
                        stringResource(R.string.card_status_done)
                    } else {
                        stringResource(R.string.card_status_remaining, card.card.requiredCount - card.currentCount)
                    },
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
        if (card.card.validUntilMillis != null || card.card.nextDueDateMillis != null) {
            Spacer(Modifier.height(2.dp))
            if (card.card.validUntilMillis != null) {
                DateRow(
                    icon = Icons.Default.CreditCard,
                    label = stringResource(R.string.card_label_valid_until),
                    value = formatDate(card.card.validUntilMillis),
                    isWarning = card.isExpired,
                )
            }
            if (card.card.nextDueDateMillis != null) {
                DateRow(
                    icon = Icons.Default.Event,
                    label = stringResource(R.string.card_label_next_due),
                    value = formatDate(card.card.nextDueDateMillis),
                    isWarning = false,
                )
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
                text = stringResource(R.string.card_long_press_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSwipe) {
                    Text(text = stringResource(R.string.card_increment_one), fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onDetail) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text = stringResource(R.string.card_detail))
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
    isWarning: Boolean,
) {
    val valueColor = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
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
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = if (isWarning) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

private fun formatDate(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(millis))
}
