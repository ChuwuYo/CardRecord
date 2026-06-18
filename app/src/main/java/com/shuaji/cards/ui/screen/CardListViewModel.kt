package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardWithCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

/**
 * 主页 UI 用的卡片视图：实体 + 实时笔数 + 是否过期。
 *
 * 把 [currentCount] 和 [isExpired] 提前算好，UI 拿到的就是「显示需要的全部」，
 * 不再需要从 ViewModel 外拿辅助数据。
 */
data class CardUi(
    val card: CardEntity,
    val currentCount: Int,
    val isExpired: Boolean,
    val lastSwipeAtMillis: Long?,
)

/** 主页 List 中的"分组"：一组卡片 + 标题（文件夹名 / "全部"） */
data class CardListGroup(
    val key: String,
    val title: String,
    val colorArgb: Int,
    val cards: List<CardUi>,
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
    val allCards: List<CardUi> = emptyList(),
    val folders: List<CardFolderEntity> = emptyList(),
    val filter: FolderFilter = FolderFilter.All,
    val layoutMode: ListLayoutMode = ListLayoutMode.LIST,
    val grouped: List<CardListGroup> = emptyList(),
) {
    val visibleCards: List<CardUi> get() = grouped.flatMap { it.cards }
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

    private val nowProvider: () -> Long = { System.currentTimeMillis() }

    /** Repository 流 → UI 流：包成 [CardUi]，把过期判定提前算好。 */
    private fun observeCardUis() =
        repository.observeCards().map { list ->
            list.map { it.toCardUi(nowProvider()) }
        }

    val uiState: StateFlow<ListUiState> =
        combine(
            observeCardUis(),
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
    fun deleteCard(card: CardUi) {
        pendingRestore.value = card.card
        viewModelScope.launch { repository.deleteCard(card.card) }
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
     * 主页快捷记一笔：写一条流水。currentCount 由 SQL COUNT 实时算，
     * 写完 Flow 立刻把新的 CardUi 推给 UI 刷新。
     *
     * 上限由 UI 控制（达标后按钮 disabled / 进度条满格不可点），ViewModel 不二次校验。
     */
    fun swipe(cardId: Long) {
        viewModelScope.launch {
            repository.recordSwipe(cardId)
        }
    }
}

/**
 * 把 [CardWithCount] 包装成 UI 用的 [CardUi]。
 *
 * - [isExpired] = `validUntilMillis != null && now > validUntilMillis`
 *   （设置了就该有"已过期"提示，存在即消费）
 */
private fun CardWithCount.toCardUi(now: Long): CardUi =
    CardUi(
        card = card,
        currentCount = currentCount,
        isExpired = card.validUntilMillis?.let { now > it } == true,
        lastSwipeAtMillis = lastSwipeAtMillis,
    )

/**
 * 按当前 [flt] 把 [cards] 分成 [CardListGroup] 列表。
 *
 * 排序：
 * - filter=All 时：每个文件夹一组 + "未分类"组（若有）
 * - filter=Folder/Unfiled 时：单组
 * - 组内：filter=All 按 progress 升序（最接近达标的在最上面），其他按更新时间倒序
 */
internal fun groupCardsForList(
    cards: List<CardUi>,
    folders: List<CardFolderEntity>,
    flt: FolderFilter,
): List<CardListGroup> {
    fun progressOf(c: CardUi): Float = if (c.card.requiredCount == 0) 100f else c.currentCount.toFloat() / c.card.requiredCount.toFloat()

    fun orderForAll(c: CardUi): Float = -progressOf(c)

    fun orderForFolder(c: CardUi): Long = -c.card.createdAtMillis

    return when (flt) {
        is FolderFilter.All -> {
            val groups = mutableListOf<CardListGroup>()
            folders.forEach { f ->
                val inFolder = cards.filter { it.card.folderId == f.id }
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
            val unfiled = cards.filter { it.card.folderId == null }
            if (unfiled.isNotEmpty()) {
                val sorted = unfiled.sortedBy { orderForAll(it) }
                groups +=
                    CardListGroup(
                        key = "unfiled",
                        title = "", // 未分类组：标题在 UI 层按 isAllGroup 本地化渲染
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
                    .filter { it.card.folderId == flt.folderId }
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
                    .filter { it.card.folderId == null }
                    .sortedBy { orderForFolder(it) }
            listOf(
                CardListGroup(
                    key = "unfiled",
                    title = "", // 未分类组：标题在 UI 层按 isAllGroup 本地化渲染
                    colorArgb = 0,
                    cards = unfiled,
                    isAllGroup = true,
                ),
            )
        }
    }
}
