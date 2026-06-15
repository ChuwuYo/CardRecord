package com.shuaji.cards.ui.screen

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.data.backup.BackupException
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.data.backup.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页状态机。
 *
 * - [Idle] — 默认态，按钮可点
 * - [Working] — 导入 / 导出中，按钮 disable + 显示进度 + 可见「取消」按钮
 * - [Done] — 完成（带消息 / 错误），按钮可点
 *
 * 用 [StateFlow] 暴露给 Compose，[MutableStateFlow] 是单源，UI 只读。
 */
sealed interface SettingsUiState {
    data object Idle : SettingsUiState

    data object Working : SettingsUiState

    data class Done(
        val kind: ResultKind,
        val message: String,
    ) : SettingsUiState

    enum class ResultKind { EXPORT_OK, IMPORT_OK, ERROR }
}

class SettingsViewModel(
    private val backup: BackupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /**
     * 导出。失败弹 [SettingsUiState.Done(kind=ERROR)]，UI 转 Snackbar。
     * 协程被取消（CancellationException）→ 静默回到 Idle，不弹错误 Snackbar。
     */
    fun export(uri: Uri) {
        viewModelScope.launch {
            _state.value = SettingsUiState.Working
            try {
                val rows = backup.export(uri)
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.EXPORT_OK,
                        message = "已导出 $rows 条记录到备份文件",
                    )
            } catch (e: CancellationException) {
                // 用户主动取消 → 静默回到 Idle
                _state.value = SettingsUiState.Idle
                throw e
            } catch (e: BackupException) {
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.ERROR,
                        message = "导出失败：${e.message}",
                    )
            } catch (e: Exception) {
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.ERROR,
                        message = "导出失败：${e.message ?: "未知错误"}",
                    )
            }
        }
    }

    /**
     * 导入。失败弹 [SettingsUiState.Done(kind=ERROR)]，UI 转 Snackbar。
     * 协程被取消（CancellationException）→ 静默回到 Idle，不弹错误 Snackbar。
     */
    fun import(
        uri: Uri,
        mode: ImportMode,
    ) {
        viewModelScope.launch {
            _state.value = SettingsUiState.Working
            try {
                val result = backup.import(uri, mode)
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.IMPORT_OK,
                        message = formatImportMessage(result, mode),
                    )
            } catch (e: CancellationException) {
                // 用户主动取消 → 静默回到 Idle（事务已自动 ROLLBACK，DB 状态完好）
                _state.value = SettingsUiState.Idle
                throw e
            } catch (e: BackupException) {
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.ERROR,
                        message = "导入失败：${e.message}",
                    )
            } catch (e: Exception) {
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.ERROR,
                        message = "导入失败：${e.message ?: "未知错误"}",
                    )
            }
        }
    }

    /**
     * 取消当前正在跑的导入 / 导出。
     *
     * 调 [BackupRepository.cancelActive] → 当前 Job 收到 `CancellationException` →
     * 写库事务自动 ROLLBACK（如果当前在 withTransaction 块里）→ export/import 的
     * catch 块捕获后回到 Idle。
     */
    fun cancel() {
        backup.cancelActive()
    }

    /** 用户关闭 / 看到 Snackbar 后回到 Idle。 */
    fun acknowledge() {
        _state.value = SettingsUiState.Idle
    }

    /**
     * 把 [ImportResult] 拼成一行可读消息。
     *
     * 拼接顺序：模式前缀 → 主体（卡/文件夹/流水）→ 副提示（跳过/重名/FK 校验失败），
     * 任何副提示为 0 就不出现对应字段，让消息保持简洁。
     */
    private fun formatImportMessage(
        result: ImportResult,
        mode: ImportMode,
    ): String {
        val modeLabel =
            when (mode) {
                ImportMode.REPLACE -> "覆盖"
                ImportMode.MERGE -> "追加"
            }
        val main =
            "$modeLabel 成功：${result.cardsAdded} 张卡，" +
                "${result.foldersAdded} 个文件夹，${result.transactionsAdded} 笔流水"
        val extras =
            buildList {
                if (result.transactionsSkipped > 0) {
                    add("${result.transactionsSkipped} 笔流水因引用不存在的卡被跳过")
                }
                if (result.cardsSkippedInvalidFolder > 0) {
                    add("${result.cardsSkippedInvalidFolder} 张卡的文件夹引用无效，已置为未分组")
                }
                if (result.duplicateFolderNames > 0) {
                    add("${result.duplicateFolderNames} 个文件夹与现库重名")
                }
                if (result.duplicateCardNames > 0) {
                    add("${result.duplicateCardNames} 张卡与现库重名")
                }
            }
        return if (extras.isEmpty()) main else "$main（${extras.joinToString("；")}）"
    }
}
