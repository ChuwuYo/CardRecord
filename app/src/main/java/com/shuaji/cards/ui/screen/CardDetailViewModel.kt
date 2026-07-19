package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.core.OneShotEventQueue
import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.SwipeRecordResult
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.CardWithCount
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.data.local.cardTypeEnum
import com.shuaji.cards.data.local.isExpiredAt
import com.shuaji.cards.data.local.isValidCardMonthDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 详情页 UI 用的视图。
 *
 * - [currentCount] 当前已刷笔数（实时计算）
 * - [requiredCount] 年度免年费所需笔数
 * - [isExpired] 是否已过有效期
 * - [swipes] 该卡全部流水（按时间倒序）—— 详情页「流水列表」直接渲染这个序列，
 *   每行用 [TransactionEntity.id] 做单笔删除的 key
 *
 * 流水表只保存 id、所属卡片 id 与时间戳；UI 列表展示全部历史。
 * 窗口外历史仍保留，因此列表行数不一定等于 [currentCount]。
 */
data class CardDetailUi(
    val card: com.shuaji.cards.data.local.CardEntity,
    val currentCount: Int,
    val isExpired: Boolean,
    val lastSwipeAtMillis: Long?,
    val cycle: AnnualFeeCycle,
    val swipes: List<TransactionEntity> = emptyList(),
) {
    val requiredCount: Int get() = card.requiredCount
    val selectedCardType: CardType?
        get() = card.cardTypeEnum.takeUnless { it == CardType.UNSPECIFIED }
    val statementDay: Int?
        get() = card.statementDay?.takeIf { selectedCardType == CardType.CREDIT && it.isValidCardMonthDay() }
    val repaymentDay: Int?
        get() = card.repaymentDay?.takeIf { selectedCardType == CardType.CREDIT && it.isValidCardMonthDay() }
    val hasDetailInfo: Boolean
        get() =
            selectedCardType != null ||
                card.bank.isNotBlank() ||
                card.cardNumberMasked.isNotBlank() ||
                card.validUntilMillis != null ||
                card.nextDueDateMillis != null ||
                statementDay != null ||
                repaymentDay != null ||
                card.note.isNotBlank()

    fun isCurrentPeriod(transaction: TransactionEntity): Boolean = cycle.includes(transaction.occurredAtMillis)
}

sealed interface CardDetailUiState {
    data object Loading : CardDetailUiState

    data class Loaded(
        val detail: CardDetailUi,
    ) : CardDetailUiState

    data object Missing : CardDetailUiState
}

sealed interface CardDetailEvent {
    data object Deleted : CardDetailEvent

    data object DeleteFailed : CardDetailEvent

    data object WriteFailed : CardDetailEvent
}

class CardDetailViewModel(
    private val repository: CardRepository,
    private val cardId: Long,
) : ViewModel() {
    private val nowProvider: () -> Instant = { Instant.now() }
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
    private var deleteJob: Job? = null

    private val swipeFeedbackQueue = OneShotEventQueue<SwipeFeedback>()
    val swipeFeedback: Flow<SwipeFeedback> = swipeFeedbackQueue.events

    /**
     * 详情页主状态由 Repository 的单一派生快照提供，避免卡片计数与历史列表
     * 分属两个订阅而出现短暂不一致。
     */
    private val eventQueue = OneShotEventQueue<CardDetailEvent>()
    val events: Flow<CardDetailEvent> = eventQueue.events

    val uiState: StateFlow<CardDetailUiState> =
        repository
            .observeCardDetails(cardId)
            .map { snapshot ->
                snapshot?.let {
                    CardDetailUiState.Loaded(it.card.toDetailUi(nowProvider(), zoneIdProvider(), it.swipes))
                } ?: CardDetailUiState.Missing
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardDetailUiState.Loading)

    /** 详情页"记一笔"：成功交给观察流刷新，拒绝原因通过一次性反馈交给页面。 */
    fun recordSwipe() {
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
                eventQueue.emit(CardDetailEvent.WriteFailed)
            }
        }
    }

    /**
     * 详情页"重置年度笔数"：只删除当前统计窗口内流水。
     * UI 上有 AlertDialog 二次确认，到这一层是用户已确认。
     */
    fun resetCardCycle() {
        launchWrite { repository.resetCardCycle(cardId) }
    }

    /**
     * 详情页"单笔删除"：流水列表每行垃圾桶按钮触发。
     * 删完 Repository 重新派生 currentCount，流水列表自动少一行。
     */
    fun deleteSwipe(id: Long) {
        launchWrite { repository.deleteTransaction(id) }
    }

    fun deleteCard() {
        if (deleteJob?.isActive == true) return
        val card = (uiState.value as? CardDetailUiState.Loaded)?.detail?.card ?: return
        deleteJob =
            viewModelScope.launch {
                try {
                    val event =
                        if (repository.deleteCard(card)) {
                            CardDetailEvent.Deleted
                        } else {
                            CardDetailEvent.DeleteFailed
                        }
                    eventQueue.emit(event)
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    eventQueue.emit(CardDetailEvent.DeleteFailed)
                }
            }
    }

    private fun launchWrite(block: suspend () -> Boolean) {
        viewModelScope.launch {
            try {
                if (!block()) eventQueue.emit(CardDetailEvent.WriteFailed)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                eventQueue.emit(CardDetailEvent.WriteFailed)
            }
        }
    }
}

private fun CardWithCount.toDetailUi(
    now: Instant,
    zoneId: ZoneId,
    swipes: List<TransactionEntity>,
): CardDetailUi =
    CardDetailUi(
        card = card,
        currentCount = currentCount,
        isExpired = card.isExpiredAt(now, zoneId),
        lastSwipeAtMillis = lastSwipeAtMillis,
        cycle = cycle,
        swipes = swipes,
    )
