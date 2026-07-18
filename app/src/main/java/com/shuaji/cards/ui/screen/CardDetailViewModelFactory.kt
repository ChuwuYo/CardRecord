package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * 工厂：为带 [cardId] 参数的 ViewModel 提供。
 */
class CardDetailViewModelFactory(
    private val repository: com.shuaji.cards.data.CardRepository,
    private val cardId: Long,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras,
    ): T {
        require(modelClass.isAssignableFrom(CardDetailViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return CardDetailViewModel(repository, cardId) as T
    }
}
