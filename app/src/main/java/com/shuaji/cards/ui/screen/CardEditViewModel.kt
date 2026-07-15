package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.ImageSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 表单状态：所有用户可控字段。
 *
 * 不再含 `currentCount`——它从 transactions 表 `COUNT(*)` 算，编辑表单
 * 也就没有"手动改笔数"这个 UI 入口了（用户唯一改笔数的方式就是
 * 详情页/主页"记一笔"按钮写一条流水）。
 */
data class CardEditUiState(
    val name: String = "",
    val bank: String = "",
    val cardNumberMasked: String = "",
    val requiredCount: String = "6",
    val validUntilMillis: Long? = null,
    val nextDueDateMillis: Long? = null,
    val colorArgb: Int = 0xFF0061A4.toInt(),
    val note: String = "",
    // 卡面三态
    val imageSourceType: ImageSourceType = ImageSourceType.PROVIDER,
    val imageProviderKey: String? = CardNetworkProvider.VISA.key,
    val imageUri: String? = null,
    val originalImageUri: String? = null,
    val acquiredImageUris: Set<String> = emptySet(),
    val cardOrientation: CardOrientation = CardOrientation.LANDSCAPE,
    val folderId: Long? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isClosing: Boolean = false,
    val saved: Boolean = false,
    val editingId: Long? = null,
) {
    val canSave: Boolean
        get() = !isSaving && !isClosing && name.isNotBlank() && requiredCount.toIntOrNull()?.let { it > 0 } == true
}

class CardEditViewModel(
    private val repository: CardRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CardEditUiState())
    val uiState: StateFlow<CardEditUiState> = _uiState.asStateFlow()

    /** 供编辑表单下拉选择使用 */
    val folders: StateFlow<List<CardFolderEntity>> =
        repository
            .observeFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                val c = entity.card
                _uiState.update {
                    it.copy(
                        name = c.name,
                        bank = c.bank,
                        cardNumberMasked = c.cardNumberMasked,
                        requiredCount = c.requiredCount.toString(),
                        validUntilMillis = c.validUntilMillis,
                        nextDueDateMillis = c.nextDueDateMillis,
                        colorArgb = c.colorArgb,
                        note = c.note,
                        imageSourceType =
                            runCatching {
                                ImageSourceType.valueOf(c.imageSourceType)
                            }.getOrDefault(ImageSourceType.NONE),
                        imageProviderKey = c.imageProviderKey,
                        imageUri = c.imageUri,
                        originalImageUri = c.imageUri,
                        acquiredImageUris = emptySet(),
                        cardOrientation =
                            runCatching {
                                CardOrientation.valueOf(c.cardOrientation)
                            }.getOrDefault(CardOrientation.LANDSCAPE),
                        folderId = c.folderId,
                        editingId = c.id,
                        isLoading = false,
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun update(transform: (CardEditUiState) -> CardEditUiState) {
        _uiState.update { current ->
            if (current.isSaving || current.isClosing || current.saved) current else transform(current)
        }
    }

    fun selectUserImage(uri: String) {
        _uiState.update { current ->
            if (current.isSaving || current.isClosing || current.saved) {
                current
            } else {
                current.copy(
                    imageUri = uri,
                    imageSourceType = ImageSourceType.USER,
                    acquiredImageUris = current.acquiredImageUris + uri,
                )
            }
        }
    }

    fun save() {
        val state = beginSaving() ?: return
        viewModelScope.launch {
            try {
                val required = state.requiredCount.toInt()
                val existingId = state.editingId
                val preserved =
                    if (existingId != null) {
                        repository.observeCard(existingId).first()
                    } else {
                        null
                    }
                val entity =
                    CardEntity(
                        id = existingId ?: 0L,
                        name = state.name.trim(),
                        bank = state.bank.trim(),
                        cardNumberMasked = state.cardNumberMasked.trim(),
                        requiredCount = required,
                        validUntilMillis = state.validUntilMillis,
                        nextDueDateMillis = state.nextDueDateMillis,
                        colorArgb = state.colorArgb,
                        note = state.note,
                        imageUri = persistedImageUri(state.imageSourceType, state.imageUri),
                        imageSourceType = state.imageSourceType.name,
                        imageProviderKey = state.imageProviderKey.takeIf { state.imageSourceType == ImageSourceType.PROVIDER },
                        cardOrientation = state.cardOrientation.name,
                        folderId = state.folderId,
                        createdAtMillis = preserved?.card?.createdAtMillis ?: System.currentTimeMillis(),
                    )
                val id = repository.upsertCard(entity)
                _uiState.update {
                    it.copy(
                        imageUri = entity.imageUri,
                        imageProviderKey = entity.imageProviderKey,
                        isSaving = false,
                        saved = true,
                        editingId = if (existingId == null) id else existingId,
                    )
                }
            } finally {
                _uiState.update { if (it.saved) it else it.copy(isSaving = false) }
            }
        }
    }

    /** 保存和关闭竞争同一份原子状态，先开始的一方阻止另一方进入。 */
    fun beginClosing(): CardEditUiState? {
        while (true) {
            val current = _uiState.value
            if (current.isSaving || current.isClosing || current.saved) return null
            if (_uiState.compareAndSet(current, current.copy(isClosing = true))) return current
        }
    }

    /** 候选 URI 只有在全库没有其他 USER 卡片引用时才可释放。 */
    suspend fun releasableImageUris(retainedUri: String?): Set<String> {
        val state = _uiState.value
        val candidates = obsoleteImageUris(state.originalImageUri, state.acquiredImageUris, retainedUri)
        return releasableImageUris(candidates, repository.referencedUserImageUris())
    }

    private fun beginSaving(): CardEditUiState? {
        while (true) {
            val current = _uiState.value
            if (!current.canSave) return null
            if (_uiState.compareAndSet(current, current.copy(isSaving = true))) return current
        }
    }
}

internal fun persistedImageUri(
    sourceType: ImageSourceType,
    imageUri: String?,
): String? = imageUri.takeIf { sourceType == ImageSourceType.USER }

internal fun obsoleteImageUris(
    originalUri: String?,
    acquiredUris: Set<String>,
    retainedUri: String?,
): Set<String> = (acquiredUris + listOfNotNull(originalUri)) - setOfNotNull(retainedUri)

internal fun releasableImageUris(
    candidates: Set<String>,
    referencedUris: Set<String>,
): Set<String> = candidates - referencedUris
