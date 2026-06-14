package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardWithCount
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 详情页 UI 用的视图。
 *
 * - [currentCount] 当前已刷笔数（实时计算）
 * - [requiredCount] 年度免年费所需笔数
 * - [isExpired] 是否已过有效期
 */
data class CardDetailUi(
    val card: com.shuaji.cards.data.local.CardEntity,
    val currentCount: Int,
    val isExpired: Boolean,
    val lastSwipeAtMillis: Long?,
) {
    val requiredCount: Int get() = card.requiredCount
}

class CardDetailViewModel(
    private val repository: CardRepository,
    private val cardId: Long,
) : ViewModel() {
    private val nowProvider: () -> Long = { System.currentTimeMillis() }

    val card: StateFlow<CardDetailUi?> =
        repository
            .observeCard(cardId)
            .map { cwc -> cwc?.toDetailUi(nowProvider()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

private fun CardWithCount.toDetailUi(now: Long): CardDetailUi =
    CardDetailUi(
        card = card,
        currentCount = currentCount,
        isExpired = card.validUntilMillis?.let { now > it } == true,
        lastSwipeAtMillis = lastSwipeAtMillis,
    )
