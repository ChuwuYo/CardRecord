package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CardDetailViewModel(
    private val repository: CardRepository,
    private val cardId: Long,
) : ViewModel() {
    val card: StateFlow<CardEntity?> =
        repository
            .observeCard(cardId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transactions: StateFlow<List<TransactionEntity>> =
        repository
            .observeTransactions(cardId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun recordTransaction(
        merchant: String,
        amountCents: Long?,
        note: String,
    ) {
        viewModelScope.launch {
            repository.recordTransaction(
                TransactionEntity(
                    cardId = cardId,
                    merchant = merchant,
                    amountCents = amountCents,
                    note = note,
                ),
            )
        }
    }

    fun deleteTransaction(t: TransactionEntity) {
        viewModelScope.launch { repository.deleteTransaction(t) }
    }

    fun deleteCard() {
        viewModelScope.launch {
            card.value?.let { repository.deleteCard(it) }
        }
    }

    fun resetCycle() {
        viewModelScope.launch { repository.resetCycle(cardId) }
    }
}
