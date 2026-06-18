package com.shuaji.cards.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.ShuajiApplication
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.ui.component.CardVisual
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 详情页：**字段全消费审计**。
 *
 * 用户在编辑页能填的每个字段（name / bank / cardNumberMasked / requiredCount /
 * validUntilMillis / nextDueDateMillis / colorArgb / note / imageSourceType /
 * cardOrientation / folderId），在这里都至少有"展示"的归宿。
 *
 * 流水表瘦到 2 字段（card_id, occurred_at_millis）后，**每一行流水
 * 都在 UI 有归宿**——「流水列表」section 把 `current.swipes` 全部按时间
 * 倒序展示出来，行数 == currentCount，零死数据。
 *
 * 操作上只保留：记一笔（FAB） / 重置（顶部按钮） / 编辑 / 删除。
 *
 * v1.4.1: 详情页加完整流水列表 section（v1.4.0 只显示一行最近时间是错的，
 * 流水表保留全部行，UI 必须有对应行数）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    cardId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as ShuajiApplication
    val viewModel: CardDetailViewModel =
        viewModel(
            factory = CardDetailViewModelFactory(app.container.repository, cardId),
        )
    val card by viewModel.card.collectAsStateWithLifecycle()

    var showResetDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var swipeToDelete by remember { mutableStateOf<TransactionEntity?>(null) }

    val titleLoading = stringResource(R.string.detail_title_loading)
    val backCd = stringResource(R.string.common_back)
    val deleteCardCd = stringResource(R.string.detail_action_delete)
    val resetCd = stringResource(R.string.detail_action_reset)
    val editCd = stringResource(R.string.detail_action_edit)
    val loadingText = stringResource(R.string.detail_loading)
    val defaultName = stringResource(R.string.card_default_name)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = card?.card?.name?.ifBlank { defaultName } ?: titleLoading, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backCd)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = deleteCardCd)
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = resetCd)
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = editCd)
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
        floatingActionButton = {
            ExtendedFabRecord(
                onClick = { viewModel.recordSwipe() },
            )
        },
    ) { padding ->
        val current = card
        if (current == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(loadingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. 卡面
            item {
                CardVisual(card = current.card)
            }
            // 2. 过期提示（如果设置了 validUntil 且已过期）
            if (current.isExpired) {
                item { ExpiredBanner() }
            }
            // 3. 进度块
            item {
                ProgressBlock(
                    currentCount = current.currentCount,
                    requiredCount = current.requiredCount,
                )
            }
            // 4. 信息区：发卡行 / 卡号 / 备注（先看卡片元数据）
            item {
                CardInfoSection(detail = current)
            }
            // 5. 流水列表（按时间倒序，全部 N 行——零死数据，每行可单笔删除）
            item {
                SwipeListSection(
                    swipes = current.swipes,
                    onRequestDelete = { swipeToDelete = it },
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.detail_reset_dialog_title)) },
            text = { Text(stringResource(R.string.detail_reset_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetCardCycle()
                    showResetDialog = false
                }) { Text(stringResource(R.string.detail_reset_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.detail_delete_dialog_title)) },
            text = { Text(stringResource(R.string.detail_delete_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCard()
                    showDeleteDialog = false
                    onBack()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // 单笔删除流水：每行垃圾桶按钮 → 二次确认 → ViewModel.deleteSwipe
    swipeToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { swipeToDelete = null },
            title = { Text(stringResource(R.string.detail_delete_swipe_dialog_title)) },
            text = { Text(stringResource(R.string.detail_delete_swipe_dialog_message, formatDateTime(target.occurredAtMillis))) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSwipe(target.id)
                    swipeToDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { swipeToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun ExtendedFabRecord(onClick: () -> Unit) {
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(stringResource(R.string.detail_action_record)) },
    )
}

/**
 * 流水列表 section：把 swipes 全部按时间倒序展示出来，每行可单笔删除。
 *
 * - 标题行：左边"刷卡记录"，右边"已刷 N 笔"（= 列表行数）
 * - 行：序号 + 时间戳 + 垃圾桶按钮
 * - 空态：引导用户点 FAB 记第一笔
 *
 * 单笔删除走 AlertDialog 二次确认（与"重置""删卡"同一档次的破坏性操作，
 * 都需要在 UI 层兜底），不在按钮上"点了就删"。
 */
@Composable
private fun SwipeListSection(
    swipes: List<TransactionEntity>,
    onRequestDelete: (TransactionEntity) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            // 标题行
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.detail_label_swipes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.detail_swipe_count, swipes.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (swipes.isEmpty()) {
                Text(
                    text = stringResource(R.string.detail_swipes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                swipes.forEachIndexed { index, txn ->
                    if (index > 0) {
                        DividerLine()
                    }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.detail_swipe_index, index + 1),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            text = formatDateTime(txn.occurredAtMillis),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        // 单笔删除按钮 —— 触发 onRequestDelete 弹出二次确认
                        IconButton(
                            onClick = { onRequestDelete(txn) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.detail_action_delete_swipe),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpiredBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.card_status_expired),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ProgressBlock(
    currentCount: Int,
    requiredCount: Int,
) {
    val progress =
        if (requiredCount > 0) (currentCount.toFloat() / requiredCount.toFloat()).coerceIn(0f, 1f) else 0f
    val isDone = currentCount >= requiredCount
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.card_count_format, currentCount, requiredCount),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Spacer(Modifier.size(6.dp))
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
                            stringResource(R.string.card_status_remaining, requiredCount - currentCount)
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                color = if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/**
 * 字段消费区：把 CardEntity 里所有「用户可填」的字段都展示出来。
 *
 * 凡是字段存在就要有消费路径，否则就是「写而不读」（用户填了看不到，毫无意义）。
 * 当前覆盖：name / bank / cardNumberMasked / requiredCount / validUntilMillis /
 * nextDueDateMillis / note / folderId。cardOrientation 在 [CardVisual] 里消费，
 * colorArgb 也是 [CardVisual] 里消费。
 */
@Composable
private fun CardInfoSection(detail: CardDetailUi) {
    val c = detail.card
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            InfoRow(
                icon = Icons.Default.CreditCard,
                label = stringResource(R.string.detail_label_bank),
                value = c.bank,
            )
            DividerLine()
            InfoRow(
                icon = Icons.Default.CreditCard,
                label = stringResource(R.string.detail_label_card_number),
                value = c.cardNumberMasked,
            )
            if (c.validUntilMillis != null) {
                DividerLine()
                InfoRow(
                    icon = Icons.Default.CreditCard,
                    label = stringResource(R.string.card_label_valid_until),
                    value = formatDate(c.validUntilMillis),
                    valueColor = if (detail.isExpired) MaterialTheme.colorScheme.error else null,
                )
            }
            if (c.nextDueDateMillis != null) {
                DividerLine()
                InfoRow(
                    icon = Icons.Default.Event,
                    label = stringResource(R.string.card_label_next_due),
                    value = formatDate(c.nextDueDateMillis),
                )
            }
            if (c.note.isNotBlank()) {
                DividerLine()
                InfoRow(
                    icon = Icons.AutoMirrored.Filled.Note,
                    label = stringResource(R.string.detail_label_note),
                    value = c.note,
                    multiline = true,
                )
            }
        }
    }
}

@Composable
private fun DividerLine() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    multiline: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = if (multiline) 6 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDate(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(Date(millis))
}

private fun formatDateTime(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}
