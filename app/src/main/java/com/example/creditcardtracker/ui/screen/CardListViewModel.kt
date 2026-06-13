package com.example.creditcardtracker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.creditcardtracker.data.CreditCardRepository
import com.example.creditcardtracker.data.local.CreditCardEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 列表页：聚合所有卡片 + 整体进度统计。
 */
class CardListViewModel(
    private val repository: CreditCardRepository,
) : ViewModel() {
    val cards: StateFlow<List<CreditCardEntity>> =
        repository
            .observeCards()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun archive(card: CreditCardEntity) {
        viewModelScope.launch { repository.archiveCard(card.id, true) }
    }

    fun delete(card: CreditCardEntity) {
        viewModelScope.launch { repository.deleteCard(card) }
    }

    fun resetCycle(card: CreditCardEntity) {
        viewModelScope.launch { repository.resetCycle(card.id) }
    }
}
