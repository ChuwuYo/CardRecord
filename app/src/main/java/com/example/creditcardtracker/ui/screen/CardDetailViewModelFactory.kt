package com.example.creditcardtracker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * 工厂：为带 [cardId] 参数的 ViewModel 提供。
 */
class CardDetailViewModelFactory(
    private val repository: com.example.creditcardtracker.data.CreditCardRepository,
    private val cardId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras,
    ): T = CardDetailViewModel(repository, cardId) as T
}
