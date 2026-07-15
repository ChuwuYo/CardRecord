package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.SwipeRecordResult
import com.shuaji.cards.data.local.CardWithCount
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 详情页 UI 用的视图。
 *
 * - [currentCount] 当前已刷笔数（实时计算）
 * - [requiredCount] 年度免年费所需笔数
 * - [isExpired] 是否已过有效期
 * - [swipes] 该卡全部流水（按时间倒序）—— 详情页「流水列表」直接渲染这个序列，
 *   每行用 [TransactionEntity.id] 做单笔删除的 key
 *
 * 流水表瘦到 2 字段后，每行只剩时间戳 + id；UI 列表展示全部历史。
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

    fun isCurrentPeriod(transaction: TransactionEntity): Boolean = cycle.includes(transaction.occurredAtMillis)
}

class CardDetailViewModel(
    private val repository: CardRepository,
    private val cardId: Long,
) : ViewModel() {
    private val nowProvider: () -> Long = { System.currentTimeMillis() }

    private val _swipeFeedback = MutableSharedFlow<SwipeFeedback>(extraBufferCapacity = 1)
    val swipeFeedback: SharedFlow<SwipeFeedback> = _swipeFeedback.asSharedFlow()

    /**
     * 详情页主状态由 Repository 的单一派生快照提供，避免卡片计数与历史列表
     * 分属两个订阅而出现短暂不一致。
     */
    val card: StateFlow<CardDetailUi?> =
        repository
            .observeCardDetails(cardId)
            .map { snapshot -> snapshot?.let { it.card.toDetailUi(nowProvider(), it.swipes) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 详情页"记一笔"：成功交给观察流刷新，拒绝原因通过一次性反馈交给页面。 */
    fun recordSwipe() {
        viewModelScope.launch {
            when (val result = repository.recordSwipe(cardId)) {
                is SwipeRecordResult.Recorded -> Unit
                is SwipeRecordResult.CountingNotStarted ->
                    _swipeFeedback.emit(SwipeFeedback.CountingNotStarted(result.startDate))
                SwipeRecordResult.CardMissing -> _swipeFeedback.emit(SwipeFeedback.CardMissing)
            }
        }
    }

    /**
     * 详情页"重置年度笔数"：只删除当前统计窗口内流水。
     * UI 上有 AlertDialog 二次确认，到这一层是用户已确认。
     */
    fun resetCardCycle() {
        viewModelScope.launch { repository.resetCardCycle(cardId) }
    }

    /**
     * 详情页"单笔删除"：流水列表每行垃圾桶按钮触发。
     * 删完 Repository 重新派生 currentCount，流水列表自动少一行。
     */
    fun deleteSwipe(id: Long) {
        viewModelScope.launch { repository.deleteTransaction(id) }
    }

    fun deleteCard() {
        viewModelScope.launch {
            card.value?.let { repository.deleteCard(it.card) }
        }
    }
}

private fun CardWithCount.toDetailUi(
    now: Long,
    swipes: List<TransactionEntity>,
): CardDetailUi =
    CardDetailUi(
        card = card,
        currentCount = currentCount,
        isExpired = card.validUntilMillis?.let { now > it } == true,
        lastSwipeAtMillis = lastSwipeAtMillis,
        cycle = cycle,
        swipes = swipes,
    )
