package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 主页布局模式 */
enum class ListLayoutMode { LIST, GRID }

/**
 * 过滤模式：
 * - [All] 全部卡片（不按文件夹过滤），按文件夹分组在 UI 层处理
 * - [Folder] 显示某个文件夹下的卡
 * - [Unfiled] 显示 folder_id 为空的卡
 */
sealed interface FolderFilter {
    data object All : FolderFilter

    data object Unfiled : FolderFilter

    data class Folder(
        val folderId: Long,
        val folderName: String,
    ) : FolderFilter
}

/** 主页 List 中的"分组"：一组卡片 + 标题（文件夹名 / "全部"） */
data class CardListGroup(
    val key: String,
    val title: String,
    val colorArgb: Int,
    val cards: List<CardEntity>,
    val isAllGroup: Boolean,
)

/**
 * 列表 UI 总状态。
 *
 * - [allCards] 永远包含所有卡片（不跟随 [filter] 变），用于顶部"总进度"
 * - [visibleCards] 跟随 [filter] 变
 * - [grouped] 始终按文件夹分组（filter=Unfiled 时只有一组"未分类"）
 */
data class ListUiState(
    val allCards: List<CardEntity> = emptyList(),
    val folders: List<CardFolderEntity> = emptyList(),
    val filter: FolderFilter = FolderFilter.All,
    val layoutMode: ListLayoutMode = ListLayoutMode.LIST,
    val grouped: List<CardListGroup> = emptyList(),
) {
    val visibleCards: List<CardEntity> get() = grouped.flatMap { it.cards }
}

/**
 * 列表页：聚合所有卡片 + 文件夹 + 整体进度统计 + 撤销删除的临时标记。
 */
class CardListViewModel(
    private val repository: CardRepository,
) : ViewModel() {
    private val _filter = MutableStateFlow<FolderFilter>(FolderFilter.All)
    val filter: StateFlow<FolderFilter> = _filter

    private val _layoutMode = MutableStateFlow(ListLayoutMode.LIST)
    val layoutMode: StateFlow<ListLayoutMode> = _layoutMode

    /** 最近一次被删除的卡名（用于 snackbar 撤销） */
    private val _deletedCardName = MutableStateFlow<String?>(null)
    val deletedCardName: StateFlow<String?> = _deletedCardName

    // 暂存最近删除的卡，供 undoDelete 恢复；只在 ViewModel 内部流转，不对外暴露。
    @Suppress("ktlint:standard:backing-property-naming")
    private val pendingRestore = MutableStateFlow<CardEntity?>(null)

    val uiState: StateFlow<ListUiState> =
        combine(
            repository.observeCards(),
            repository.observeFolders(),
            _filter,
            _layoutMode,
        ) { cards, folders, flt, mode ->
            val grouped = groupCardsForList(cards, folders, flt)
            ListUiState(
                allCards = cards,
                folders = folders,
                filter = flt,
                layoutMode = mode,
                grouped = grouped,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ListUiState(),
        )

    fun selectFilter(flt: FolderFilter) {
        _filter.value = flt
    }

    fun toggleLayoutMode() {
        _layoutMode.value =
            if (_layoutMode.value == ListLayoutMode.LIST) ListLayoutMode.GRID else ListLayoutMode.LIST
    }

    /** 长按删除：先复制一份存起来再删，给 [markDeleted] 触发 snackbar 留时间。 */
    fun deleteCard(card: CardEntity) {
        pendingRestore.value = card
        viewModelScope.launch { repository.deleteCard(card) }
    }

    fun markDeleted(name: String) {
        _deletedCardName.value = name
    }

    fun undoDelete() {
        val card = pendingRestore.value ?: return
        viewModelScope.launch {
            repository.upsertCard(card)
            pendingRestore.value = null
            _deletedCardName.value = null
        }
    }

    fun consumeDeletedEvent() {
        _deletedCardName.value = null
    }

    /**
     * 主页快捷记一笔：增加当前卡的 currentCount，不写 transaction 表（轻量）。
     * 上限 = requiredCount。
     */
    fun incrementCount(cardId: Long) {
        viewModelScope.launch {
            val card = repository.getCard(cardId) ?: return@launch
            val newCount = (card.currentCount + 1).coerceAtMost(card.requiredCount)
            if (newCount == card.currentCount) return@launch
            repository.upsertCard(card.copy(currentCount = newCount))
        }
    }
}

/**
 * 按当前 [flt] 把 [cards] 分成 [CardListGroup] 列表。
 *
 * 排序：
 * - filter=All 时：每个文件夹一组 + "未分类"组（若有）
 * - filter=Folder/Unfiled 时：单组
 * - 组内：filter=All 按 progress 升序（最接近达标的在最上面），其他按更新时间倒序
 */
internal fun groupCardsForList(
    cards: List<CardEntity>,
    folders: List<CardFolderEntity>,
    flt: FolderFilter,
): List<CardListGroup> {
    fun progressOf(c: CardEntity): Float = if (c.requiredCount == 0) 100f else c.currentCount.toFloat() / c.requiredCount.toFloat()

    fun orderForAll(c: CardEntity): Float = -progressOf(c)

    fun orderForFolder(c: CardEntity): Long = -c.createdAtMillis

    return when (flt) {
        is FolderFilter.All -> {
            val groups = mutableListOf<CardListGroup>()
            folders.forEach { f ->
                val inFolder = cards.filter { it.folderId == f.id }
                if (inFolder.isNotEmpty()) {
                    val sorted = inFolder.sortedBy { orderForAll(it) }
                    groups +=
                        CardListGroup(
                            key = "f-${f.id}",
                            title = f.name,
                            colorArgb = f.colorArgb,
                            cards = sorted,
                            isAllGroup = false,
                        )
                }
            }
            val unfiled = cards.filter { it.folderId == null }
            if (unfiled.isNotEmpty()) {
                val sorted = unfiled.sortedBy { orderForAll(it) }
                groups +=
                    CardListGroup(
                        key = "unfiled",
                        title = "未分类",
                        colorArgb = 0,
                        cards = sorted,
                        isAllGroup = true,
                    )
            }
            groups
        }
        is FolderFilter.Folder -> {
            val folder = folders.firstOrNull { it.id == flt.folderId }
            val title = folder?.name ?: flt.folderName
            val color = folder?.colorArgb ?: 0
            val inFolder =
                cards
                    .filter { it.folderId == flt.folderId }
                    .sortedBy { orderForFolder(it) }
            listOf(
                CardListGroup(
                    key = "f-${flt.folderId}",
                    title = title,
                    colorArgb = color,
                    cards = inFolder,
                    isAllGroup = false,
                ),
            )
        }
        is FolderFilter.Unfiled -> {
            val unfiled =
                cards
                    .filter { it.folderId == null }
                    .sortedBy { orderForFolder(it) }
            listOf(
                CardListGroup(
                    key = "unfiled",
                    title = "未分类",
                    colorArgb = 0,
                    cards = unfiled,
                    isAllGroup = true,
                ),
            )
        }
    }
}
