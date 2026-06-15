package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardWithCount
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 详情页 UI 用的视图。
 *
 * - [currentCount] 当前已刷笔数（实时计算）
 * - [requiredCount] 年度免年费所需笔数
 * - [isExpired] 是否已过有效期
 * - [swipes] 该卡全部流水（按时间倒序）—— 详情页「流水列表」直接渲染这个序列
 *
 * 流水表瘦到 2 字段后，每行只剩时间戳；UI 列表把 `swipes` 全部展示出来
 * （行数 == `currentCount`），保证「写一行就有 UI 一行」，不写死数据。
 */
data class CardDetailUi(
    val card: com.shuaji.cards.data.local.CardEntity,
    val currentCount: Int,
    val isExpired: Boolean,
    val lastSwipeAtMillis: Long?,
    val swipes: List<Long> = emptyList(),
) {
    val requiredCount: Int get() = card.requiredCount
}

class CardDetailViewModel(
    private val repository: CardRepository,
    private val cardId: Long,
) : ViewModel() {
    private val nowProvider: () -> Long = { System.currentTimeMillis() }

    /**
     * 详情页主状态：把「卡本身 + 笔数」和「流水列表」两个流合并。
     * 任何一个变化（记一笔 / 重置 / 编辑卡）都触发 UI 刷新。
     */
    val card: StateFlow<CardDetailUi?> =
        combine(
            repository.observeCard(cardId),
            repository.observeTransactions(cardId),
        ) { cwc, swipes ->
            cwc?.toDetailUi(nowProvider(), swipes.map { it.occurredAtMillis })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 详情页"记一笔"：写一条流水，currentCount 由 SQL COUNT 重算。
     */
    fun recordSwipe() {
        viewModelScope.launch { repository.recordSwipe(cardId) }
    }

    /**
     * 详情页"重置年度笔数"：删该卡所有流水。
     * UI 上有 AlertDialog 二次确认，到这一层是用户已确认。
     */
    fun resetCardCycle() {
        viewModelScope.launch { repository.resetCardCycle(cardId) }
    }

    fun deleteCard() {
        viewModelScope.launch {
            card.value?.let { repository.deleteCard(it.card) }
        }
    }
}

private fun CardWithCount.toDetailUi(
    now: Long,
    swipes: List<Long>,
): CardDetailUi =
    CardDetailUi(
        card = card,
        currentCount = currentCount,
        isExpired = card.validUntilMillis?.let { now > it } == true,
        lastSwipeAtMillis = lastSwipeAtMillis,
        swipes = swipes,
    )
