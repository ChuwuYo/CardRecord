package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 主页布局模式 */
enum class ListLayoutMode { LIST, GRID }

/**
 * 过滤模式：
 * - [ALL] 全部卡片（不按文件夹过滤），按文件夹分组在 UI 层处理
 * - [FOLDER] 显示某个文件夹下的卡
 * - [UNFILED] 显示 folder_id 为空的卡
 */
sealed interface FolderFilter {
    data object All : FolderFilter

    data object Unfiled : FolderFilter

    data class InFolder(
        val folderId: Long,
    ) : FolderFilter
}

data class ListUiState(
    val cards: List<CardEntity> = emptyList(),
    val folders: List<CardFolderEntity> = emptyList(),
    val filter: FolderFilter = FolderFilter.All,
    val layoutMode: ListLayoutMode = ListLayoutMode.LIST,
)

/**
 * 列表页：聚合所有卡片 + 文件夹 + 整体进度统计。
 */
class CardListViewModel(
    private val repository: CardRepository,
) : ViewModel() {
    // 过滤器与布局模式：作为 StateFlow 暴露，配合 combine 出 uiState
    val filter: MutableStateFlow<FolderFilter> = MutableStateFlow(FolderFilter.All)
    val layoutMode: MutableStateFlow<ListLayoutMode> = MutableStateFlow(ListLayoutMode.LIST)

    val uiState: StateFlow<ListUiState> =
        combine(
            repository.observeCards(),
            repository.observeFolders(),
            filter,
            layoutMode,
        ) { cards, folders, flt, mode ->
            val filtered =
                when (flt) {
                    is FolderFilter.All -> cards
                    is FolderFilter.Unfiled -> cards.filter { it.folderId == null }
                    is FolderFilter.InFolder -> cards.filter { it.folderId == flt.folderId }
                }
            ListUiState(
                cards = filtered,
                folders = folders,
                filter = flt,
                layoutMode = mode,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ListUiState(),
        )

    fun setFilter(flt: FolderFilter) {
        filter.value = flt
    }

    fun setLayoutMode(mode: ListLayoutMode) {
        layoutMode.value = mode
    }

    fun toggleLayoutMode() {
        layoutMode.value =
            if (layoutMode.value == ListLayoutMode.LIST) ListLayoutMode.GRID else ListLayoutMode.LIST
    }

    fun archive(card: CardEntity) {
        viewModelScope.launch { repository.archiveCard(card.id, true) }
    }

    fun delete(card: CardEntity) {
        viewModelScope.launch { repository.deleteCard(card) }
    }

    fun resetCycle(card: CardEntity) {
        viewModelScope.launch { repository.resetCycle(card.id) }
    }

    /**
     * 主页快捷记一笔：增加当前卡的 currentCount，不写 transaction 表（轻量）。
     * 上限 = requiredCount。
     */
    fun incrementCount(card: CardEntity) {
        val newCount = (card.currentCount + 1).coerceAtMost(card.requiredCount)
        if (newCount == card.currentCount) return
        viewModelScope.launch {
            // 直接调底层 setCount，保持简洁
            card.let {
                repository.upsertCard(it.copy(currentCount = newCount))
            }
        }
    }
}
