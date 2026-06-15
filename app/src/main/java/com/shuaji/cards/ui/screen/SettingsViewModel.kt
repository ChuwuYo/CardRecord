package com.shuaji.cards.ui.screen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.R
import com.shuaji.cards.data.AppContainer
import com.shuaji.cards.data.SettingsDoneEvent
import com.shuaji.cards.data.backup.BackupException
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.backup.ExportSummary
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.data.backup.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *
 * **Done 事件**额外通过 [doneEvents] 推到 [com.shuaji.cards.data.AppContainer.settingsEvents]，
 * 让 `ShuajiApp` 顶层全局 SnackbarHost 也能消费——用户在 SettingsScreen 点完「导出」立刻跳到 Home
 * 也能看到「已导出 N 条」提示（P1 修：原 SettingsScreen 自带 SnackbarHost 跨页面即丢）。
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

/**
 * SettingsViewModel。
 *
 * **注入 Application**（继承 [AndroidViewModel]）而不是裸 [ViewModel]——因为本类要
 * 调 `getString(R.string.xxx, args...)` 拼多语言文案。「ViewModel 不该持有 Context」是
 * Android 架构原则，但 [AndroidViewModel] 用 Application 是官方推荐豁免——Application
 * 跟进程同生命周期，不会泄露 Activity。
 *
 * **事件流**：[doneEvents] 推到 AppContainer，让顶层 SnackbarHost 接收；同时
 * [state] 仍是 [StateFlow]，给本页面 UI（`state is Working` 决定全屏遮罩）。
 * 两者不重复：state 走页面生命周期，events 走全 app 生命周期。
 */
class SettingsViewModel(
    application: Application,
    private val backup: BackupRepository,
    private val settingsEventsSink: AppContainer? = null,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /**
     * 已经把消息拼好的 Done 事件流。
     *
     * 暴露给 SettingsScreen 本地用：组件内嵌的 SnackbarHost 也能直接订阅（兜底，万一
     * settingsEventsSink 没接上，页面内仍能弹）。
     *
     * 顶层 Snackbar 在 [com.shuaji.cards.ui.ShuajiApp] 订阅 AppContainer.settingsEvents。
     */
    private val _doneEvents = MutableSharedFlow<SettingsDoneEvent>(extraBufferCapacity = 4)
    val doneEvents: SharedFlow<SettingsDoneEvent> = _doneEvents.asSharedFlow()

    /**
     * 导出。失败弹 [SettingsUiState.Done(kind=ERROR)]，UI 转 Snackbar。
     * 协程被取消（CancellationException）→ 静默回到 Idle，不弹错误 Snackbar。
     */
    fun export(uri: Uri) {
        viewModelScope.launch {
            _state.value = SettingsUiState.Working
            try {
                val summary: ExportSummary = backup.export(uri)
                val message =
                    if (summary.isEmpty) {
                        getApplication<Application>().getString(R.string.settings_result_export_empty)
                    } else {
                        getApplication<Application>().getString(
                            R.string.settings_result_export_success_with_count,
                            summary.cardCount,
                            summary.folderCount,
                            summary.transactionCount,
                        )
                    }
                val event = SettingsDoneEvent(message = message, isError = false)
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.EXPORT_OK,
                        message = message,
                    )
                emitDone(event)
            } catch (e: CancellationException) {
                // 用户主动取消 → 静默回到 Idle
                _state.value = SettingsUiState.Idle
                throw e
            } catch (e: BackupException) {
                val message = errorMessage(R.string.settings_result_export_failed, e.message ?: "未知原因")
                val event = SettingsDoneEvent(message = message, isError = true)
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.ERROR,
                        message = message,
                    )
                emitDone(event)
            } catch (e: Exception) {
                val message = errorMessage(R.string.settings_result_export_failed, e.message ?: "未知错误")
                val event = SettingsDoneEvent(message = message, isError = true)
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.ERROR,
                        message = message,
                    )
                emitDone(event)
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
                val message = formatImportMessage(result, mode)
                val event = SettingsDoneEvent(message = message, isError = false)
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.IMPORT_OK,
                        message = message,
                    )
                emitDone(event)
            } catch (e: CancellationException) {
                _state.value = SettingsUiState.Idle
                throw e
            } catch (e: BackupException) {
                val message = errorMessage(R.string.settings_result_import_failed, e.message ?: "未知原因")
                val event = SettingsDoneEvent(message = message, isError = true)
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.ERROR,
                        message = message,
                    )
                emitDone(event)
            } catch (e: Exception) {
                val message = errorMessage(R.string.settings_result_import_failed, e.message ?: "未知错误")
                val event = SettingsDoneEvent(message = message, isError = true)
                _state.value =
                    SettingsUiState.Done(
                        kind = SettingsUiState.ResultKind.ERROR,
                        message = message,
                    )
                emitDone(event)
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

    /**
     * 用户关闭 / 看到 Snackbar 后回到 Idle。
     *
     * P3 修：acknowledge 改成"延迟到 Snackbar 显示完后"——避免快速来回：done
     * 触发 Snackbar → 用户立刻 acknowledge → state 回 Idle → 但 SharedFlow emit
     * 已经被外面 collect → 顶层 Snackbar 还是会弹，造成"消息和状态机对不上"的诡异感。
     *
     * 现在策略：acknowledge 只清 [state]；SharedFlow emit 在 launch 里 emitDone 时已经发出去，
     * 跟 state 变化解耦。如果 Snackbar 没消费消息就 acknowledge 走人，事件会留在 SharedFlow 队列
     * 里等下次订阅（buffer=4，不会丢）。
     */
    fun acknowledge() {
        _state.value = SettingsUiState.Idle
    }

    /** 把事件推给两个出口：本页面 UI 的 doneEvents（兜底）+ AppContainer.emitSettings（全局）。 */
    private suspend fun emitDone(event: SettingsDoneEvent) {
        _doneEvents.emit(event)
        settingsEventsSink?.emitSettings(event)
    }

    /** 错误文案统一封装：「XXX 失败：<cause>」。 */
    private fun errorMessage(
        @androidx.annotation.StringRes templateRes: Int,
        cause: String,
    ): String = getApplication<Application>().getString(templateRes, cause)

    /**
     * 把 [ImportResult] 拼成一行可读消息。
     *
     * 拼接顺序：模式前缀 → 主体（卡/文件夹/流水）→ 副提示（跳过/重名/FK 校验失败 / 卡面 URI 跨设备失效）。
     * 任何副提示为 0 就不出现对应字段，让消息保持简洁。
     *
     * **P2 国际化**：所有硬编码中文搬到 strings.xml，本函数用 `getString(resId, args)` 拼装。
     */
    private fun formatImportMessage(
        result: ImportResult,
        mode: ImportMode,
    ): String {
        val app = getApplication<Application>()
        val mainResId =
            when (mode) {
                ImportMode.REPLACE -> R.string.settings_result_import_replace_with_count
                ImportMode.MERGE -> R.string.settings_result_import_merge_with_count
            }
        val main = app.getString(mainResId, result.cardsAdded, result.foldersAdded, result.transactionsAdded)
        val extras =
            buildList {
                if (result.transactionsSkipped > 0) {
                    add(
                        app.getString(
                            R.string.settings_result_extras_transactions_skipped,
                            result.transactionsSkipped,
                        ),
                    )
                }
                if (result.cardsSkippedInvalidFolder > 0) {
                    add(
                        app.getString(
                            R.string.settings_result_extras_cards_invalid_folder,
                            result.cardsSkippedInvalidFolder,
                        ),
                    )
                }
                if (result.duplicateFolderNames > 0) {
                    add(
                        app.getString(
                            R.string.settings_result_extras_duplicate_folders,
                            result.duplicateFolderNames,
                        ),
                    )
                }
                if (result.duplicateCardNames > 0) {
                    add(
                        app.getString(
                            R.string.settings_result_extras_duplicate_cards,
                            result.duplicateCardNames,
                        ),
                    )
                }
                if (result.imageUriUserCount > 0) {
                    add(
                        app.getString(
                            R.string.settings_result_image_uri_potentially_broken,
                            result.imageUriUserCount,
                        ),
                    )
                }
            }
        return if (extras.isEmpty()) main else "$main（${extras.joinToString("；")}）"
    }
}
