package com.shuaji.cards.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.SettingsRepository
import com.shuaji.cards.data.ThemeSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AppThemeViewModel(
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val themeSettings: StateFlow<ThemeSettings?> =
        settingsRepo.themeSettings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
}
