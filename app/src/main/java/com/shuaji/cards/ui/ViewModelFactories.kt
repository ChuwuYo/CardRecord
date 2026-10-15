package com.shuaji.cards.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.shuaji.cards.ShuajiApplication
import com.shuaji.cards.requireShuajiApplication
import com.shuaji.cards.ui.screen.CardDetailViewModel
import com.shuaji.cards.ui.screen.CardEditViewModel
import com.shuaji.cards.ui.screen.CardFolderViewModel
import com.shuaji.cards.ui.screen.CardListViewModel
import com.shuaji.cards.ui.screen.SettingsViewModel
import com.shuaji.cards.ui.theme.AppThemeViewModel

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
            initializer {
                CardEditViewModel(
                    repository = app().container.repository,
                    cardId = createSavedStateHandle().toRoute<CardEdit>().cardId,
                )
            }
        }
    val Detail =
        viewModelFactory {
            initializer {
                CardDetailViewModel(
                    repository = app().container.repository,
                    cardId = createSavedStateHandle().toRoute<CardDetail>().cardId,
                )
            }
        }
    val Folders =
        viewModelFactory {
            initializer { CardFolderViewModel(app().container.repository) }
        }
    val AppTheme =
        viewModelFactory {
            initializer { AppThemeViewModel(app().container.settings) }
        }
    val Settings =
        viewModelFactory {
            initializer {
                val container = app().container
                SettingsViewModel(
                    application = app(),
                    backup = container.backup,
                    emitSettingsEvent = container::emitSettings,
                    settingsRepo = container.settings,
                    reminderStore = container.reminderStore,
                    requestReminderReschedule = container::requestReminderReschedule,
                )
            }
        }

    private fun CreationExtras.app(): ShuajiApplication {
        val application =
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("Application missing from CreationExtras")
        return application.requireShuajiApplication()
    }
}
