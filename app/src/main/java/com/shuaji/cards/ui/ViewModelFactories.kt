package com.shuaji.cards.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shuaji.cards.ShuajiApplication
import com.shuaji.cards.ui.screen.CardEditViewModel
import com.shuaji.cards.ui.screen.CardFolderViewModel
import com.shuaji.cards.ui.screen.CardListViewModel
import com.shuaji.cards.ui.screen.SettingsViewModel

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
    val Settings =
        viewModelFactory {
            initializer {
                val container = app().container
                // P1 修：把 AppContainer 注入到 ViewModel。
                // ViewModel 拿到的是 AppContainer 接口（不是 DefaultAppContainer），
                // 通过接口的 `emitSettings()` 推送事件——比让 ViewModel 反射拿
                // MutableSharedFlow 干净，接口隔离原则也好。
                SettingsViewModel(
                    application = app(),
                    backup = container.backup,
                    settingsEventsSink = container,
                )
            }
        }

    private fun CreationExtras.app(): ShuajiApplication {
        val application =
            this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("Application missing from CreationExtras")
        return application as ShuajiApplication
    }
}
