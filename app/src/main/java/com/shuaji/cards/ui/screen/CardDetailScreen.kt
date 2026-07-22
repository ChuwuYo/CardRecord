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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.DateToken
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.requireShuajiApplication
import com.shuaji.cards.ui.component.CardVisual
import com.shuaji.cards.ui.component.CycleProgressContent
import com.shuaji.cards.ui.component.CycleProgressVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 卡片元数据、当前周期进度与完整流水的详情页。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    cardId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.requireShuajiApplication()
    val viewModel: CardDetailViewModel =
        viewModel(
            factory = cardDetailViewModelFactory(app.container.repository, cardId),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val card = (state as? CardDetailUiState.Loaded)?.detail

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
    val title =
        when (val currentState = state) {
            CardDetailUiState.Loading -> titleLoading
            CardDetailUiState.Missing -> stringResource(R.string.card_missing)
            is CardDetailUiState.Loaded ->
                currentState.detail.card.name
                    .ifBlank { defaultName }
        }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.swipeFeedback.collect { feedback ->
            val message =
                when (feedback) {
                    is SwipeFeedback.CountingNotStarted ->
                        context.getString(R.string.card_record_not_started_feedback, feedback.startDate.toString())
                    SwipeFeedback.CardMissing -> context.getString(R.string.card_missing)
                }
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CardDetailEvent.Deleted -> onBack()
                CardDetailEvent.DeleteFailed ->
                    snackbarHostState.showSnackbar(context.getString(R.string.card_delete_failed))
                CardDetailEvent.WriteFailed ->
                    snackbarHostState.showSnackbar(context.getString(R.string.common_operation_failed))
            }
        }
    }
    LaunchedEffect(card?.cycle) {
        if (card?.cycle?.canRecord == false) showResetDialog = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backCd)
                    }
                },
                actions = {
                    if (card != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = deleteCardCd)
                        }
                        if (card.cycle.canRecord) {
                            IconButton(onClick = { showResetDialog = true }) {
                                Icon(Icons.Default.Refresh, contentDescription = resetCd)
                            }
                        }
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = editCd)
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
        floatingActionButton = {
            card?.let { loaded ->
                ExtendedFabRecord(
                    onClick = { viewModel.recordSwipe() },
                    enabled = loaded.cycle.canRecord,
                    disabledReason =
                        when (loaded.cycle) {
                            is AnnualFeeCycle.Upcoming -> stringResource(R.string.card_record_disabled_upcoming)
                            AnnualFeeCycle.Overdue -> stringResource(R.string.card_record_disabled_overdue)
                            else -> ""
                        },
                )
            }
        },
    ) { padding ->
        when (val currentState = state) {
            CardDetailUiState.Loading,
            CardDetailUiState.Missing,
            -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (currentState is CardDetailUiState.Loading) loadingText else stringResource(R.string.card_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is CardDetailUiState.Loaded -> {
                val current = currentState.detail
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { CardVisual(card = current.card, userImageModel = current.userImageModel) }
                    if (current.isExpired) {
                        item { ExpiredBanner() }
                    }
                    item {
                        ProgressBlock(
                            cycle = current.cycle,
                            currentCount = current.currentCount,
                            requiredCount = current.requiredCount,
                        )
                    }
                    if (current.hasDetailInfo) {
                        item { CardInfoSection(detail = current) }
                    }
                    swipeListItems(
                        detail = current,
                        onRequestDelete = { swipeToDelete = it },
                    )
                }
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
private fun ExtendedFabRecord(
    onClick: () -> Unit,
    enabled: Boolean,
    disabledReason: String,
) {
    androidx.compose.material3.ExtendedFloatingActionButton(
        onClick = { if (enabled) onClick() },
        modifier =
            Modifier.semantics {
                if (!enabled) {
                    disabled()
                    stateDescription = disabledReason
                }
            },
        containerColor =
            if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor =
            if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(stringResource(R.string.detail_action_record)) },
    )
}

/** 流水与详情共用外层 LazyColumn，避免把全部历史一次性组合进单个 item。 */
private fun LazyListScope.swipeListItems(
    detail: CardDetailUi,
    onRequestDelete: (TransactionEntity) -> Unit,
) {
    val swipes = detail.swipes
    item(key = "swipe-header") {
        SwipeListHeader(count = swipes.size)
    }
    if (swipes.isEmpty()) {
        item(key = "swipe-empty") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = stringResource(R.string.detail_swipes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    } else {
        itemsIndexed(
            items = swipes,
            key = { _, transaction -> transaction.id },
        ) { index, transaction ->
            SwipeListRow(
                index = index,
                transaction = transaction,
                isCurrentPeriod = detail.isCurrentPeriod(transaction),
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

@Composable
private fun SwipeListHeader(count: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
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
                text = pluralStringResource(R.plurals.detail_swipe_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SwipeListRow(
    index: Int,
    transaction: TransactionEntity,
    isCurrentPeriod: Boolean,
    onRequestDelete: (TransactionEntity) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatDateTime(transaction.occurredAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (!isCurrentPeriod) {
                    Text(
                        text = stringResource(R.string.detail_swipe_historical),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = { onRequestDelete(transaction) },
                modifier = Modifier.size(48.dp),
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
    cycle: com.shuaji.cards.data.AnnualFeeCycle,
    currentCount: Int,
    requiredCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CycleProgressContent(
                cycle = cycle,
                currentCount = currentCount,
                requiredCount = requiredCount,
                variant = CycleProgressVariant.DETAIL,
            )
        }
    }
}

/**
 * 详情字段消费区。
 *
 * 卡名、颜色和朝向由 [CardVisual] 展示，所需笔数由 [ProgressBlock] 展示，文件夹
 * 由列表分组消费；这里只渲染非空的补充信息，避免空银行名或卡号占据整行。
 */
@Composable
private fun CardInfoSection(detail: CardDetailUi) {
    val c = detail.card
    val rows =
        listOfNotNull(
            detail.selectedCardType?.let { cardType ->
                CardInfoRow(
                    icon = Icons.Default.CreditCard,
                    label = stringResource(R.string.detail_card_type),
                    value =
                        stringResource(
                            when (cardType) {
                                CardType.DEBIT -> R.string.card_type_debit
                                CardType.CREDIT -> R.string.card_type_credit
                                CardType.UNSPECIFIED -> R.string.card_type_unspecified
                            },
                        ),
                )
            },
            c.bank.takeIf(String::isNotBlank)?.let {
                CardInfoRow(
                    icon = Icons.Default.CreditCard,
                    label = stringResource(R.string.detail_label_bank),
                    value = it,
                )
            },
            c.cardNumberMasked.takeIf(String::isNotBlank)?.let {
                CardInfoRow(
                    icon = Icons.Default.CreditCard,
                    label = stringResource(R.string.detail_label_card_number),
                    value = it,
                )
            },
            c.validUntilMillis?.let {
                CardInfoRow(
                    icon = Icons.Default.CreditCard,
                    label = stringResource(R.string.card_label_valid_until),
                    value = DateToken.format(it),
                    valueColor = if (detail.isExpired) MaterialTheme.colorScheme.error else null,
                )
            },
            c.nextDueDateMillis?.let {
                CardInfoRow(
                    icon = Icons.Default.Event,
                    label = stringResource(R.string.card_label_next_due),
                    value = DateToken.formatAnnualDue(it),
                )
            },
            detail.statementDay?.let {
                CardInfoRow(
                    icon = Icons.Default.Event,
                    label = stringResource(R.string.detail_statement_day),
                    value = stringResource(R.string.card_day_of_month, it),
                )
            },
            detail.repaymentDay?.let {
                CardInfoRow(
                    icon = Icons.Default.Event,
                    label = stringResource(R.string.detail_repayment_day),
                    value = stringResource(R.string.card_day_of_month, it),
                )
            },
            c.note.takeIf(String::isNotBlank)?.let {
                CardInfoRow(
                    icon = Icons.AutoMirrored.Filled.Note,
                    label = stringResource(R.string.detail_label_note),
                    value = it,
                    multiline = true,
                )
            },
        )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            rows.forEachIndexed { index, row ->
                if (index > 0) DividerLine()
                InfoRow(
                    icon = row.icon,
                    label = row.label,
                    value = row.value,
                    valueColor = row.valueColor,
                    multiline = row.multiline,
                )
            }
        }
    }
}

private data class CardInfoRow(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val valueColor: androidx.compose.ui.graphics.Color? = null,
    val multiline: Boolean = false,
)

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

private fun formatDateTime(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}
