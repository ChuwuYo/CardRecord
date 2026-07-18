package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shuaji.cards.data.CardRepository

/**
 * 工厂：为带 [cardId] 参数的 ViewModel 提供。
 */
fun cardDetailViewModelFactory(
    repository: CardRepository,
    cardId: Long,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            CardDetailViewModel(repository, cardId)
        }
    }
