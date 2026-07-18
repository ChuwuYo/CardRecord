package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.core.OneShotEventQueue
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.FolderWithCardCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface CardFolderUiState {
    data object Loading : CardFolderUiState

    data class Ready(
        val folders: List<FolderWithCardCount>,
    ) : CardFolderUiState
}

sealed interface CardFolderEvent {
    data object WriteFailed : CardFolderEvent
}

/** 文件夹管理：增、删、改、查。 */
class CardFolderViewModel(
    private val repository: CardRepository,
) : ViewModel() {
    private val eventQueue = OneShotEventQueue<CardFolderEvent>()
    val events: Flow<CardFolderEvent> = eventQueue.events

    val uiState: StateFlow<CardFolderUiState> =
        repository
            .observeFoldersWithCardCounts()
            .map<List<FolderWithCardCount>, CardFolderUiState>(CardFolderUiState::Ready)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardFolderUiState.Loading)

    fun create(
        name: String,
        colorArgb: Int,
    ) {
        if (name.isBlank()) return
        launchWrite {
            repository.createFolder(name.trim(), colorArgb)
            true
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
        launchWrite { repository.updateFolder(folder.copy(name = newName.trim(), colorArgb = newColor)) }
    }

    fun delete(folder: CardFolderEntity) {
        launchWrite { repository.deleteFolder(folder) }
    }

    private fun launchWrite(block: suspend () -> Boolean) {
        viewModelScope.launch {
            try {
                if (!block()) eventQueue.emit(CardFolderEvent.WriteFailed)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                eventQueue.emit(CardFolderEvent.WriteFailed)
            }
        }
    }
}
