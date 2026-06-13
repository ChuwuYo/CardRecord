package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardFolderEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 文件夹管理：增、删、改、查。
 */
class CardFolderViewModel(
    private val repository: CardRepository,
) : ViewModel() {
    val folders: StateFlow<List<CardFolderEntity>> =
        repository
            .observeFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _counts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val counts: StateFlow<Map<Long, Int>> = _counts.asStateFlow()

    fun refreshCounts() {
        viewModelScope.launch {
            val list = folders.value
            val map = HashMap<Long, Int>(list.size)
            for (f in list) {
                map[f.id] = repository.countCardsInFolder(f.id)
            }
            _counts.value = map
        }
    }

    fun create(
        name: String,
        colorArgb: Int,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val order = (folders.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
            repository.upsertFolder(
                CardFolderEntity(
                    name = name.trim(),
                    colorArgb = colorArgb,
                    sortOrder = order,
                ),
            )
            refreshCounts()
        }
    }

    fun rename(
        folder: CardFolderEntity,
        newName: String,
    ) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.updateFolder(folder.copy(name = newName.trim()))
        }
    }

    fun recolor(
        folder: CardFolderEntity,
        newColor: Int,
    ) {
        viewModelScope.launch {
            repository.updateFolder(folder.copy(colorArgb = newColor))
        }
    }

    fun delete(folder: CardFolderEntity) {
        viewModelScope.launch {
            repository.deleteFolder(folder)
            refreshCounts()
        }
    }
}
