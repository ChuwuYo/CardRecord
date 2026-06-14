package com.shuaji.cards.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shuaji.cards.ShuajiApplication
import com.shuaji.cards.ui.screen.CardEditViewModel
import com.shuaji.cards.ui.screen.CardFolderViewModel
import com.shuaji.cards.ui.screen.CardListViewModel

/**
 * 轻量级 ViewModel 工厂：把 [AppContainer] 注入到 ViewModel。
 */
object ViewModelFactories {
    val List =
        viewModelFactory {
            initializer { CardListViewModel(app().container.repository) }
        }
    val Edit =
        viewModelFactory {
            initializer { CardEditViewModel(app().container.repository) }
        }
    val Folders =
        viewModelFactory {
            initializer { CardFolderViewModel(app().container.repository) }
        }

    private fun CreationExtras.app(): ShuajiApplication {
        val application =
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("Application missing from CreationExtras")
        return application as ShuajiApplication
    }
}
