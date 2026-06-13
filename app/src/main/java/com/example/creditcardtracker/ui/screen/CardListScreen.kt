package com.example.creditcardtracker.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.CreditCardOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.creditcardtracker.data.local.CardFolderEntity
import com.example.creditcardtracker.data.local.CreditCardEntity
import com.example.creditcardtracker.ui.ViewModelFactories
import com.example.creditcardtracker.ui.component.CardListItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardListScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenFolders: () -> Unit,
) {
    val viewModel: CardListViewModel = viewModel(factory = ViewModelFactories.List)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
                            text = "刷记",
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
                actions = {
                    LayoutModeToggle(
                        mode = uiState.layoutMode,
                        onToggle = viewModel::toggleLayoutMode,
                    )
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
                text = { Text("添加卡片") },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                FilterBar(
                    filter = uiState.filter,
                    folders = uiState.folders,
                    onFilterChange = viewModel::setFilter,
                    onManageFolders = onOpenFolders,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                AnimatedVisibility(
                    visible = uiState.cards.isEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    EmptyState()
                }
                if (uiState.cards.isNotEmpty()) {
                    when (uiState.layoutMode) {
                        ListLayoutMode.LIST ->
                            ListLayout(
                                state = uiState,
                                haptics = haptics,
                                onOpen = onOpen,
                                onDelete = { cardToDelete = it },
                                onIncrement = viewModel::incrementCount,
                            )
                        ListLayoutMode.GRID ->
                            GridLayout(
                                state = uiState,
                                haptics = haptics,
                                onOpen = onOpen,
                                onDelete = { cardToDelete = it },
                                onIncrement = viewModel::incrementCount,
                            )
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
private fun LayoutModeToggle(
    mode: ListLayoutMode,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (mode == ListLayoutMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
            contentDescription = if (mode == ListLayoutMode.LIST) "切换为网格" else "切换为列表",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterBar(
    filter: FolderFilter,
    folders: List<CardFolderEntity>,
    onFilterChange: (FolderFilter) -> Unit,
    onManageFolders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label =
        when (filter) {
            is FolderFilter.All -> "全部卡片"
            is FolderFilter.Unfiled -> "未分类"
            is FolderFilter.InFolder -> folders.firstOrNull { it.id == filter.folderId }?.name ?: "已删除文件夹"
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (filter is FolderFilter.InFolder) Icons.Default.Folder else Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    when (filter) {
                        is FolderFilter.All -> "${folders.size} 个文件夹"
                        else -> "切换"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("全部卡片") },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    onClick = {
                        onFilterChange(FolderFilter.All)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("未分类") },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    onClick = {
                        onFilterChange(FolderFilter.Unfiled)
                        expanded = false
                    },
                )
                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    folders.forEach { f ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(f.colorArgb)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            onClick = {
                                onFilterChange(FolderFilter.InFolder(f.id))
                                expanded = false
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                DropdownMenuItem(
                    text = { Text("管理文件夹…") },
                    leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onManageFolders()
                    },
                )
            }
        }
    }
}

@Composable
private fun ListLayout(
    state: ListUiState,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onOpen: (Long) -> Unit,
    onDelete: (CreditCardEntity) -> Unit,
    onIncrement: (CreditCardEntity) -> Unit,
) {
    val grouped = remember(state.cards, state.filter) { groupCardsForList(state.cards, state.folders, state.filter) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { OverallProgress(state.cards) }
        grouped.forEach { (folderHeader, cards) ->
            if (folderHeader != null) {
                item(key = "header-${folderHeader.id}") {
                    FolderHeader(folderHeader)
                }
            }
            items(cards, key = { it.id }) { card ->
                CardListItem(
                    card = card,
                    onClick = { onOpen(card.id) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete(card)
                    },
                    onIncrement = { onIncrement(card) },
                    onDetail = { onOpen(card.id) },
                )
            }
        }
    }
}

@Composable
private fun GridLayout(
    state: ListUiState,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onOpen: (Long) -> Unit,
    onDelete: (CreditCardEntity) -> Unit,
    onIncrement: (CreditCardEntity) -> Unit,
) {
    val grouped = remember(state.cards, state.filter) { groupCardsForList(state.cards, state.folders, state.filter) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { OverallProgress(state.cards) }
        grouped.forEach { (folderHeader, cards) ->
            if (folderHeader != null) {
                item(key = "header-${folderHeader.id}") {
                    FolderHeader(folderHeader)
                }
            }
            // 实际两列：用 chunked(2) 后每行一项
            cards.chunked(2).forEachIndexed { rowIndex, pair ->
                item(key = "grid-${folderHeader?.id ?: "all"}-$rowIndex") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        pair.forEach { card ->
                            Box(modifier = Modifier.weight(1f)) {
                                CardListItem(
                                    card = card,
                                    onClick = { onOpen(card.id) },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onDelete(card)
                                    },
                                    onIncrement = { onIncrement(card) },
                                    onDetail = { onOpen(card.id) },
                                    compact = true,
                                )
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun groupCardsForList(
    cards: List<CreditCardEntity>,
    folders: List<CardFolderEntity>,
    filter: FolderFilter,
): List<Pair<CardFolderEntity?, List<CreditCardEntity>>> {
    if (filter !is FolderFilter.All) {
        return listOf(null to cards)
    }
    val byFolder = cards.groupBy { it.folderId }
    val result = mutableListOf<Pair<CardFolderEntity?, List<CreditCardEntity>>>()
    folders.forEach { f ->
        byFolder[f.id]?.let { result.add(f to it) }
    }
    byFolder[null]?.let { result.add(null to it) }
    return result
}

@Composable
private fun FolderHeader(folder: CardFolderEntity) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(folder.colorArgb)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
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
            text = "还没有卡片",
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
