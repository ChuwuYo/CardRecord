package com.example.creditcardtracker.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.creditcardtracker.data.CreditCardRepository
import com.example.creditcardtracker.data.local.CardOrientation
import com.example.creditcardtracker.data.local.CreditCardEntity
import com.example.creditcardtracker.data.local.ImageSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 表单状态：所有用户可控字段。
 */
data class CardEditUiState(
    val name: String = "",
    val bank: String = "",
    val cardNumberMasked: String = "",
    val requiredCount: String = "6",
    val currentCount: String = "0",
    val validUntilMillis: Long? = null,
    val nextDueDateMillis: Long? = null,
    val colorArgb: Int = 0xFF0061A4.toInt(),
    val note: String = "",
    // 卡面三态
    val imageSourceType: ImageSourceType = ImageSourceType.PROVIDER,
    val imageProviderKey: String? = null,
    val imageUri: String? = null,
    val cardOrientation: CardOrientation = CardOrientation.LANDSCAPE,
    val isLoading: Boolean = false,
    val saved: Boolean = false,
    val editingId: Long? = null,
) {
    val canSave: Boolean
        get() = name.isNotBlank() && requiredCount.toIntOrNull()?.let { it > 0 } == true
}

class CardEditViewModel(
    private val repository: CreditCardRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CardEditUiState())
    val uiState: StateFlow<CardEditUiState> = _uiState.asStateFlow()

    /**
     * 重置表单到初始状态。新建卡片时调用。
     */
    fun reset() {
        _uiState.value = CardEditUiState()
    }

    /**
     * 加载已有卡片数据。使用 first() 只取一次，避免 Flow 持续订阅。
     */
    fun load(cardId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entity = repository.observeCard(cardId).first()
            if (entity != null) {
                _uiState.update {
                    it.copy(
                        name = entity.name,
                        bank = entity.bank,
                        cardNumberMasked = entity.cardNumberMasked,
                        requiredCount = entity.requiredCount.toString(),
                        currentCount = entity.currentCount.toString(),
                        validUntilMillis = entity.validUntilMillis,
                        nextDueDateMillis = entity.nextDueDateMillis,
                        colorArgb = entity.colorArgb,
                        note = entity.note,
                        imageSourceType =
                            runCatching {
                                ImageSourceType.valueOf(entity.imageSourceType)
                            }.getOrDefault(ImageSourceType.NONE),
                        imageProviderKey = entity.imageProviderKey,
                        imageUri = entity.imageUri,
                        cardOrientation =
                            runCatching {
                                CardOrientation.valueOf(entity.cardOrientation)
                            }.getOrDefault(CardOrientation.LANDSCAPE),
                        editingId = entity.id,
                        isLoading = false,
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun update(transform: (CardEditUiState) -> CardEditUiState) {
        _uiState.update(transform)
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            val required = state.requiredCount.toInt()
            val current = state.currentCount.toIntOrNull()?.coerceIn(0, required) ?: 0
            val existingId = state.editingId
            val entity =
                CreditCardEntity(
                    id = existingId ?: 0L,
                    name = state.name.trim(),
                    bank = state.bank.trim(),
                    cardNumberMasked = state.cardNumberMasked.trim(),
                    requiredCount = required,
                    currentCount = current,
                    validUntilMillis = state.validUntilMillis,
                    nextDueDateMillis = state.nextDueDateMillis,
                    cycleStartMillis = System.currentTimeMillis(),
                    colorArgb = state.colorArgb,
                    note = state.note,
                    imageUri = state.imageUri,
                    imageSourceType = state.imageSourceType.name,
                    imageProviderKey = state.imageProviderKey,
                    cardOrientation = state.cardOrientation.name,
                )
            val id = repository.upsertCard(entity)
            _uiState.update { it.copy(saved = true, editingId = if (existingId == null) id else existingId) }
        }
    }
}
