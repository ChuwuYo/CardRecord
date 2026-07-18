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
            repository.insertFolder(
                CardFolderEntity(
                    name = name.trim(),
                    colorArgb = colorArgb,
                    sortOrder = order,
                ),
            )
            refreshCounts()
        }
    }

    /**
     * 一次性更新文件夹的名称和颜色（单条 UPDATE），避免拆成两次写
     * 在快速连点保存时产生两条中间状态、把 Flow 触发两次重组。
     */
    fun update(
        folder: CardFolderEntity,
        newName: String,
        newColor: Int,
    ) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.updateFolder(folder.copy(name = newName.trim(), colorArgb = newColor))
        }
    }

    fun delete(folder: CardFolderEntity) {
        viewModelScope.launch {
            repository.deleteFolder(folder)
            refreshCounts()
        }
    }
}
