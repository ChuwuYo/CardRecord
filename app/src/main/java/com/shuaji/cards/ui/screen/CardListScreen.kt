package com.shuaji.cards.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shuaji.cards.R
import com.shuaji.cards.ui.ViewModelFactories
import com.shuaji.cards.ui.component.CardListItem

/**
 * 主页：搜索 + 文件夹过滤 + 卡片列表/网格切换。
 *
 * 排序策略：filter=All 时按"距离达标"倒序（最接近达标的在最上面，提醒用户刷），
 * filter=Folder/Unfiled 时按创建时间倒序（最新添加的最上）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardListScreen(
    viewModel: CardListViewModel = viewModel(factory = ViewModelFactories.List),
    onAddCard: () -> Unit = {},
    onCardClick: (Long) -> Unit = {},
    onManageFolders: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val deletedCardName by viewModel.deletedCardName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val undoMessage = stringResource(R.string.list_delete_dialog_message_undo)
    val undoActionLabel = stringResource(R.string.list_delete_dialog_undo)
    val defaultName = stringResource(R.string.card_default_name)

    LaunchedEffect(deletedCardName) {
        val name = deletedCardName
        if (name != null) {
            val result =
                snackbarHostState.showSnackbar(
                    message = undoMessage.format(name),
                    actionLabel = undoActionLabel,
                    withDismissAction = true,
                )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else {
                viewModel.consumeDeletedEvent()
            }
        }
    }

    Scaffold(
        topBar = {
            ListTopBar(
                cardCount = state.visibleCards.size,
                onSearch = {},
                onLayoutToggle = viewModel::toggleLayoutMode,
                layoutMode = state.layoutMode,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCard,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.list_add_card)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // 进度条（仅在有卡时显示）
            AnimatedVisibility(visible = state.allCards.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                OverallProgress(state = state)
            }
            // 过滤栏
            FilterBar(
                state = state,
                onSelectFilter = viewModel::selectFilter,
                onManageFolders = onManageFolders,
            )
            // 主体：列表 / 网格
            if (state.visibleCards.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                when (state.layoutMode) {
                    ListLayoutMode.LIST ->
                        CardsList(
                            state = state,
                            onCardClick = onCardClick,
                            onLongPress = { card ->
                                viewModel.deleteCard(card)
                                viewModel.markDeleted(card.card.name.ifBlank { defaultName })
                            },
                            onSwipe = viewModel::swipe,
                        )
                    ListLayoutMode.GRID ->
                        CardsGrid(
                            state = state,
                            onCardClick = onCardClick,
                        )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListTopBar(
    cardCount: Int,
    onSearch: () -> Unit,
    onLayoutToggle: () -> Unit,
    layoutMode: ListLayoutMode,
) {
    // 用 MD3 TopAppBar 替代自定义 Surface：它会自动应用 WindowInsets.statusBars，
    // 避免标题被状态栏遮挡（之前自定义 Surface 没读 inset，标题直接画到 (0,0)）。
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.list_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = stringResource(R.string.list_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = null)
            }
            IconButton(onClick = onLayoutToggle) {
                if (layoutMode == ListLayoutMode.LIST) {
                    Icon(
                        Icons.Default.GridView,
                        contentDescription = stringResource(R.string.list_toggle_to_grid),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = stringResource(R.string.list_toggle_to_list),
                    )
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
    )
}

@Composable
private fun OverallProgress(state: ListUiState) {
    val totalRequired = state.allCards.sumOf { it.card.requiredCount }
    val totalCurrent = state.allCards.sumOf { it.currentCount }
    val percent = if (totalRequired == 0) 100 else (totalCurrent * 100 / totalRequired).coerceIn(0, 100)
    val allDone = state.allCards.isNotEmpty() && state.allCards.all { it.currentCount >= it.card.requiredCount }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.list_overall_progress),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    if (allDone) {
                        stringResource(R.string.list_overall_progress_done)
                    } else {
                        stringResource(R.string.list_overall_progress_value, totalCurrent, totalRequired, percent)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
            color = if (allDone) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    state: ListUiState,
    onSelectFilter: (FolderFilter) -> Unit,
    onManageFolders: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val anchorLabel = stringResource(R.string.list_filter_anchor_label)
    val currentLabel =
        when (val f = state.filter) {
            is FolderFilter.All -> stringResource(R.string.list_filter_all)
            is FolderFilter.Unfiled -> stringResource(R.string.list_filter_unfiled)
            is FolderFilter.Folder -> f.folderName
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            // MD3 官方做法：表单场景下的下拉应该撑满父容器宽度。
            // 用 ExposedDropdownMenuBox + OutlinedTextField.menuAnchor(MenuAnchorType) 即可。
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(anchorLabel) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.list_filter_switch),
                    )
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            )
            // 显式 heightIn 上限：文件夹数量多时出现滚动条（自带 scrollState）。
            // 同时加一个开关项"管理文件夹…"用于跳转。
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.list_filter_all)) },
                    onClick = {
                        onSelectFilter(FolderFilter.All)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.list_filter_unfiled)) },
                    onClick = {
                        onSelectFilter(FolderFilter.Unfiled)
                        expanded = false
                    },
                )
                if (state.folders.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.list_filter_folder_count, state.folders.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    state.folders.forEach { folder ->
                        val isCurrent =
                            (state.filter as? FolderFilter.Folder)?.folderId == folder.id
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = folder.name,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            leadingIcon = {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(14.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(folder.colorArgb)),
                                )
                            },
                            onClick = {
                                onSelectFilter(FolderFilter.Folder(folder.id, folder.name))
                                expanded = false
                            },
                        )
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.list_filter_manage),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
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
private fun CardsList(
    state: ListUiState,
    onCardClick: (Long) -> Unit,
    onLongPress: (CardUi) -> Unit,
    onSwipe: (Long) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        state.grouped.forEach { group ->
            item(key = "header-${group.key}") {
                FolderHeader(
                    title = group.title,
                    colorArgb = group.colorArgb,
                    isAllGroup = group.isAllGroup,
                )
            }
            items(group.cards, key = { it.card.id }) { card ->
                CardListItem(
                    card = card,
                    onClick = { onCardClick(card.card.id) },
                    onLongClick = { onLongPress(card) },
                    onSwipe = { onSwipe(card.card.id) },
                    onDetail = { onCardClick(card.card.id) },
                )
            }
        }
    }
}

@Composable
private fun FolderHeader(
    title: String,
    colorArgb: Int,
    isAllGroup: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isAllGroup) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(colorArgb)),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * grid 模式 cell 类型：把 grouped 摊平到一个 grid 流里。
 *
 * 之前 v1.3.10 用 `LazyRow + 260dp` 实现 grid，本质是「横向滚动的固定宽列」，
 * 手机屏宽 360-420dp 下 260dp 占 2/3 屏宽，看起来只放得下 1.5 张卡——这就是
 * 你说的"2/3~3/4 大小"。这里改用 `LazyVerticalGrid(GridCells.Adaptive(160dp))`：
 * 屏宽 360dp → 2 列；420dp → 2 列；600dp+ → 3+ 列自适应。
 */
private sealed interface CardGridCell {
    val key: String

    data class Header(
        val title: String,
        val colorArgb: Int,
        val isAllGroup: Boolean,
    ) : CardGridCell {
        override val key: String get() = "h-$title"
    }

    data class Item(
        val card: CardUi,
    ) : CardGridCell {
        override val key: String get() = "i-${card.card.id}"
    }
}

@Composable
private fun CardsGrid(
    state: ListUiState,
    onCardClick: (Long) -> Unit,
) {
    // 摊平 grouped → cell 流（header + cards）
    val cells =
        remember(state.grouped) {
            state.grouped.flatMap { group ->
                listOf(
                    CardGridCell.Header(
                        title = group.title,
                        colorArgb = group.colorArgb,
                        isAllGroup = group.isAllGroup,
                    ),
                ) + group.cards.map { CardGridCell.Item(it) }
            }
        }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Header 跨满整行（不是 grid cell），Item 占 1 格
        items(
            items = cells,
            key = { it.key },
            span = { cell ->
                if (cell is CardGridCell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
        ) { cell ->
            when (cell) {
                is CardGridCell.Header ->
                    FolderHeader(
                        title = cell.title,
                        colorArgb = cell.colorArgb,
                        isAllGroup = cell.isAllGroup,
                    )
                is CardGridCell.Item ->
                    CardListItem(
                        card = cell.card,
                        onClick = { onCardClick(cell.card.card.id) },
                        onLongClick = {},
                        onSwipe = {},
                        onDetail = { onCardClick(cell.card.card.id) },
                        compact = true,
                    )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(R.string.list_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.list_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
