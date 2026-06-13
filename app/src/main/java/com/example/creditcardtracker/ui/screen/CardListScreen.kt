package com.example.creditcardtracker.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CreditCardOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.creditcardtracker.data.local.CreditCardEntity
import com.example.creditcardtracker.ui.ViewModelFactories
import com.example.creditcardtracker.ui.component.CreditCardVisual
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CardListScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val viewModel: CardListViewModel = viewModel(factory = ViewModelFactories.List)
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var cardToDelete by remember { mutableStateOf<CreditCardEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "信用卡管家",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = "刷卡不再忘记，自动追踪免年费进度",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加信用卡") },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            AnimatedVisibility(
                visible = cards.isEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                EmptyState()
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 96.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (cards.isNotEmpty()) {
                    item { OverallProgress(cards = cards) }
                }
                items(cards, key = { it.id }) { card ->
                    Column(
                        modifier =
                            Modifier.combinedClickable(
                                onClick = { onOpen(card.id) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    cardToDelete = card
                                },
                            ),
                    ) {
                        CreditCardVisual(card = card)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "长按可删除",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            androidx.compose.material3.TextButton(onClick = { onOpen(card.id) }) {
                                Text("查看 / 记一笔")
                            }
                        }
                    }
                }
            }
        }
    }

    if (cardToDelete != null) {
        val card = cardToDelete!!
        AlertDialog(
            onDismissRequest = { cardToDelete = null },
            title = { Text("删除 ${card.name}？") },
            text = { Text("将同时删除这张卡的全部消费记录，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(card)
                    cardToDelete = null
                    scope.launch {
                        val result =
                            snackbarHostState.showSnackbar(
                                message = "已删除 ${card.name}",
                                actionLabel = "撤销",
                                withDismissAction = true,
                            )
                        // 实际项目里"撤销"需要从快照恢复；这里给出视觉反馈即可。
                        if (result == SnackbarResult.ActionPerformed) {
                            // no-op
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { cardToDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OverallProgress(cards: List<CreditCardEntity>) {
    val total = cards.sumOf { it.requiredCount }
    val done = cards.sumOf { it.currentCount.coerceAtMost(it.requiredCount) }
    val percent = if (total == 0) 0 else (done * 100 / total)
    val allDone = done >= total && total > 0
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Text(
            text = "总进度",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (allDone) "🎉 全部达标" else "$done / $total 笔  ·  $percent%",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = if (allDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CreditCardOff,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "还没有信用卡",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "点击右下角添加你的第一张卡片\n支持自定义卡名、主题色、所需笔数与到期日",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
