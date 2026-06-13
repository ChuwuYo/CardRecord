package com.example.creditcardtracker.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.creditcardtracker.CreditCardApp
import com.example.creditcardtracker.ui.screen.CardEditViewModel
import com.example.creditcardtracker.ui.screen.CardFolderViewModel
import com.example.creditcardtracker.ui.screen.CardListViewModel

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

    private fun CreationExtras.app(): CreditCardApp {
        val application =
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("Application missing from CreationExtras")
        return application as CreditCardApp
    }
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline factoryProducer: () -> ViewModelProvider.Factory): VM =
    viewModel(factory = factoryProducer())
