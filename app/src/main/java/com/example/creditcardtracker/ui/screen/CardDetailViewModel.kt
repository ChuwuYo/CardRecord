package com.example.creditcardtracker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.creditcardtracker.data.CreditCardRepository
import com.example.creditcardtracker.data.local.CreditCardEntity
import com.example.creditcardtracker.data.local.TransactionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CardDetailViewModel(
    private val repository: CreditCardRepository,
    private val cardId: Long,
) : ViewModel() {
    val card: StateFlow<CreditCardEntity?> =
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

    fun syncFromTransactions() {
        viewModelScope.launch { repository.syncCountFromTransactions(cardId) }
    }
}
