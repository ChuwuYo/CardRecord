package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.core.OneShotEventQueue
import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.SwipeRecordResult
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.CardWithCount
import com.shuaji.cards.data.local.cardTypeEnum
import com.shuaji.cards.data.local.isExpiredAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** 主页布局模式 */
enum class ListLayoutMode { LIST, GRID }

/**
 * 卡片筛选是与文件夹并列的视图条件，不会修改卡片归属。
 *
 * 借记卡和信用卡只匹配用户明确选择过的类型；历史卡片的“未选择”状态仍会出现在
 * [All]、[Folder] 和 [Unfiled] 中，避免升级后被错误归类。
 */
sealed interface CardFilter {
    data object All : CardFilter

    data object Debit : CardFilter

    data object Credit : CardFilter

    data object Unfiled : CardFilter

    data class Folder(
        val folderId: Long,
        val folderName: String,
    ) : CardFilter
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
    val cycle: AnnualFeeCycle,
)

data class OverallProgress(
    val current: Long,
    val required: Long,
    val percent: Int,
    val allDone: Boolean,
    val isEmpty: Boolean,
)

sealed interface SwipeFeedback {
    data class CountingNotStarted(
        val startDate: java.time.LocalDate,
    ) : SwipeFeedback

    data object CardMissing : SwipeFeedback
}

sealed interface CardListEvent {
    data object DeleteFailed : CardListEvent

    data object WriteFailed : CardListEvent
}

/** 主页 List 中的"分组"：一组卡片 + 标题（文件夹名 / "全部"） */
data class CardListGroup(
    val key: String,
    val title: String,
    val colorArgb: Int,
    val cards: List<CardUi>,
    val isUnfiledGroup: Boolean,
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
    val filter: CardFilter = CardFilter.All,
    val layoutMode: ListLayoutMode = ListLayoutMode.LIST,
    val grouped: List<CardListGroup> = emptyList(),
) {
    val visibleCards: List<CardUi> get() = grouped.flatMap { it.cards }
    val overallProgress: OverallProgress = calculateOverallProgress(allCards)
}

/**
 * 列表页：聚合所有卡片 + 文件夹 + 整体进度统计 + 删除确认状态。
 */
class CardListViewModel(
    private val repository: CardRepository,
) : ViewModel() {
    private val selectedFilter = MutableStateFlow<CardFilter>(CardFilter.All)

    private val selectedLayoutMode = MutableStateFlow(ListLayoutMode.LIST)

    private val _pendingDelete = MutableStateFlow<CardUi?>(null)
    val pendingDelete: StateFlow<CardUi?> = _pendingDelete.asStateFlow()

    private val swipeFeedbackQueue = OneShotEventQueue<SwipeFeedback>()
    val swipeFeedback: Flow<SwipeFeedback> = swipeFeedbackQueue.events
    private val eventQueue = OneShotEventQueue<CardListEvent>()
    val events: Flow<CardListEvent> = eventQueue.events
    private var deleteJob: Job? = null

    private val nowProvider: () -> Instant = { Instant.now() }
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }

    /** Repository 流 → UI 流：包成 [CardUi]，把过期判定提前算好。 */
    private fun observeCardUis() =
        repository.observeCards().map { list ->
            list.map { it.toCardUi(nowProvider(), zoneIdProvider()) }
        }

    val uiState: StateFlow<ListUiState> =
        combine(
            observeCardUis(),
            repository.observeFolders(),
            selectedFilter,
            selectedLayoutMode,
        ) { cards, folders, filter, mode ->
            val normalizedFilter = normalizeCardFilter(filter, folders)
            val grouped = groupCardsForList(cards, folders, normalizedFilter)
            ListUiState(
                allCards = cards,
                folders = folders,
                filter = normalizedFilter,
                layoutMode = mode,
                grouped = grouped,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ListUiState(),
        )

    fun selectFilter(filter: CardFilter) {
        selectedFilter.value = filter
    }

    fun toggleLayoutMode() {
        selectedLayoutMode.value =
            if (selectedLayoutMode.value == ListLayoutMode.LIST) ListLayoutMode.GRID else ListLayoutMode.LIST
    }

    /** 长按只发起确认，不在用户确认前写数据库。 */
    fun requestDelete(card: CardUi) {
        _pendingDelete.value = card
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        if (deleteJob?.isActive == true) return
        val card = _pendingDelete.value ?: return
        _pendingDelete.value = null
        deleteJob =
            viewModelScope.launch {
                try {
                    if (!repository.deleteCard(card.card)) {
                        eventQueue.emit(CardListEvent.DeleteFailed)
                    }
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    eventQueue.emit(CardListEvent.DeleteFailed)
                }
            }
    }

    /**
     * 主页快捷记一笔：写一条流水。currentCount 由 Repository 按周期实时派生，
     * 写完 Flow 立刻把新的 CardUi 推给 UI 刷新。
     *
     * 达标后仍允许继续记录真实消费；展示进度会封顶，但流水事实不应被 UI 目标值截断。
     */
    fun swipe(cardId: Long) {
        viewModelScope.launch {
            try {
                when (val result = repository.recordSwipe(cardId)) {
                    is SwipeRecordResult.Recorded -> Unit
                    is SwipeRecordResult.CountingNotStarted ->
                        swipeFeedbackQueue.emit(SwipeFeedback.CountingNotStarted(result.startDate))
                    SwipeRecordResult.CardMissing -> swipeFeedbackQueue.emit(SwipeFeedback.CardMissing)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                eventQueue.emit(CardListEvent.WriteFailed)
            }
        }
    }
}

/**
 * 文件夹可在管理页被改名或删除，因此筛选项只把 id 当作身份真源。
 * 已删除的筛选目标回退到“全部”，仍存在的目标则刷新展示名称。
 */
internal fun normalizeCardFilter(
    filter: CardFilter,
    folders: List<CardFolderEntity>,
): CardFilter =
    when (filter) {
        CardFilter.All,
        CardFilter.Debit,
        CardFilter.Credit,
        CardFilter.Unfiled,
        -> filter
        is CardFilter.Folder ->
            folders
                .firstOrNull { it.id == filter.folderId }
                ?.let { CardFilter.Folder(folderId = it.id, folderName = it.name) }
                ?: CardFilter.All
    }

/**
 * 把 [CardWithCount] 包装成 UI 用的 [CardUi]。
 *
 * - [isExpired] 按用户所在时区的自然日判断，有效期当天仍然有效
 */
private fun CardWithCount.toCardUi(
    now: Instant,
    zoneId: ZoneId,
): CardUi =
    CardUi(
        card = card,
        currentCount = currentCount,
        isExpired = card.isExpiredAt(now, zoneId),
        lastSwipeAtMillis = lastSwipeAtMillis,
        cycle = cycle,
    )

internal fun calculateOverallProgress(cards: List<CardUi>): OverallProgress {
    val participants = cards.filter { it.cycle.participatesInProgress && it.card.requiredCount > 0 }
    val required = participants.sumOf { it.card.requiredCount.toLong() }
    val current = participants.sumOf { minOf(it.currentCount, it.card.requiredCount).toLong() }
    return OverallProgress(
        current = current,
        required = required,
        percent = if (required == 0L) 0 else (current * 100L / required).toInt(),
        allDone = participants.isNotEmpty() && participants.all { it.currentCount >= it.card.requiredCount },
        isEmpty = participants.isEmpty(),
    )
}

internal fun sortCardsForOverall(cards: List<CardUi>): List<CardUi> =
    cards.sortedWith(
        compareBy<CardUi> { cycleRank(it.cycle) }
            .thenBy { it.card.requiredCount == 0 }
            .thenByDescending { cappedProgressRatio(it) }
            .thenByDescending { it.card.createdAtMillis },
    )

private fun cycleRank(cycle: AnnualFeeCycle): Int =
    when (cycle) {
        is AnnualFeeCycle.Active,
        AnnualFeeCycle.Unscheduled,
        -> 0
        AnnualFeeCycle.Overdue -> 1
        is AnnualFeeCycle.Upcoming -> 2
    }

private fun cappedProgressRatio(card: CardUi): Double =
    if (card.card.requiredCount <= 0) 0.0 else (card.currentCount.toDouble() / card.card.requiredCount).coerceAtMost(1.0)

/**
 * 按当前 [filter] 把 [cards] 分成 [CardListGroup] 列表。
 *
 * 排序：
 * - filter=All/Debit/Credit 时：先按类型筛选，再按文件夹分组
 * - filter=Folder/Unfiled 时：单组，包含未选择卡类型的历史数据
 * - 组内：全局筛选按 progress 降序，文件夹筛选按创建时间倒序
 */
internal fun groupCardsForList(
    cards: List<CardUi>,
    folders: List<CardFolderEntity>,
    filter: CardFilter,
): List<CardListGroup> =
    when (filter) {
        CardFilter.All -> groupCardsAcrossFolders(cards, folders)
        CardFilter.Debit ->
            groupCardsAcrossFolders(
                cards = cards.filter { it.card.cardTypeEnum == CardType.DEBIT },
                folders = folders,
            )
        CardFilter.Credit ->
            groupCardsAcrossFolders(
                cards = cards.filter { it.card.cardTypeEnum == CardType.CREDIT },
                folders = folders,
            )
        is CardFilter.Folder -> {
            val folder = folders.firstOrNull { it.id == filter.folderId }
            val title = folder?.name ?: filter.folderName
            val color = folder?.colorArgb ?: 0
            val inFolder =
                cards
                    .filter { it.card.folderId == filter.folderId }
                    .sortedByDescending { it.card.createdAtMillis }
            listOf(
                CardListGroup(
                    key = "f-${filter.folderId}",
                    title = title,
                    colorArgb = color,
                    cards = inFolder,
                    isUnfiledGroup = false,
                ),
            )
        }
        CardFilter.Unfiled -> {
            val unfiled =
                cards
                    .filter { it.card.folderId == null }
                    .sortedByDescending { it.card.createdAtMillis }
            listOf(
                CardListGroup(
                    key = "unfiled",
                    title = "",
                    colorArgb = 0,
                    cards = unfiled,
                    isUnfiledGroup = true,
                ),
            )
        }
    }

private fun groupCardsAcrossFolders(
    cards: List<CardUi>,
    folders: List<CardFolderEntity>,
): List<CardListGroup> {
    val cardsByFolderId = cards.groupBy { it.card.folderId }
    val groups = mutableListOf<CardListGroup>()
    folders.forEach { folder ->
        val inFolder = cardsByFolderId[folder.id].orEmpty()
        if (inFolder.isNotEmpty()) {
            groups +=
                CardListGroup(
                    key = "f-${folder.id}",
                    title = folder.name,
                    colorArgb = folder.colorArgb,
                    cards = sortCardsForOverall(inFolder),
                    isUnfiledGroup = false,
                )
        }
    }
    val unfiled = cardsByFolderId[null].orEmpty()
    if (unfiled.isNotEmpty()) {
        groups +=
            CardListGroup(
                key = "unfiled",
                title = "",
                colorArgb = 0,
                cards = sortCardsForOverall(unfiled),
                isUnfiledGroup = true,
            )
    }
    return groups
}
