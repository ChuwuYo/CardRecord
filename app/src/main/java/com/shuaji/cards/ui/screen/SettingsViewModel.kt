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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页状态机。
 *
 * - [Idle] — 默认态，按钮可点
 * - [Working] — 导入 / 导出中，按钮 disable + 显示进度 + 可见「取消」按钮
 * - [Done] — 完成，**只是个标志位**——具体消息通过 [SettingsDoneEvent] 推到顶层全局 SnackbarHost
 *
 * **P2-1 / P2-2 修**：原 `data class Done(kind, message)` 携带枚举 + 文本，但 SettingsScreen
 * 只用 `state is Done`（`kind` 和 `message` 从来没被读）。死字段违反"凡是存在就要有存在意义"——
 * 简化成 [Done] data object，标志位即可。
 *
 * **P2-3 修**：原 `doneEvents: SharedFlow` 私有 MutableSharedFlow + 公开 read-only 视图，
 * 注释说是"给组件内嵌的 SnackbarHost 兜底"——但 SettingsScreen 已经没有 SnackbarHost 了
 * （P1-3 删过）。删除整条死流。
 */
sealed interface SettingsUiState {
    data object Idle : SettingsUiState

    data object Working : SettingsUiState

    /** 完成态。Snackbar 消息走 [SettingsDoneEvent] 推到全局；state 只关心"是否忙完"。 */
    data object Done : SettingsUiState
}

/**
 * SettingsViewModel。
 *
 * **注入 Application**（继承 [AndroidViewModel]）而不是裸 [ViewModel]——因为本类要
 * 调 `getString(R.string.xxx, args...)` 拼多语言文案。「ViewModel 不该持有 Context」是
 * Android 架构原则，但 [AndroidViewModel] 用 Application 是官方推荐豁免——Application
 * 跟进程同生命周期，不会泄露 Activity。
 *
 * **P3-5 修**：[settingsEventsSink] 不再 nullable——产线 [com.shuaji.cards.ui.ViewModelFactories.Settings]
 * 必传，null 入口已经不存在，保留 `?` + `?.` 徒增分支。
 */
class SettingsViewModel(
    application: Application,
    private val backup: BackupRepository,
    private val settingsEventsSink: AppContainer,
    private val settingsRepo: com.shuaji.cards.data.SettingsRepository,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** 主题设置：UI 用 collectAsState 订阅，用户切换时自动重组 */
    val themeSettings = settingsRepo.themeSettings

    fun setThemeMode(mode: com.shuaji.cards.data.ThemeMode) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }

    fun setColorSource(source: com.shuaji.cards.data.ColorSource) {
        viewModelScope.launch { settingsRepo.setColorSource(source) }
    }

    fun setSeedColorHex(hex: String?) {
        viewModelScope.launch { settingsRepo.setSeedColorHex(hex) }
    }

    /**
     * 导出。失败弹全局 Snackbar（isError=true → ⚠️ 前缀），UI 转 Done 等用户 acknowledge。
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
                finalize(message = message, isError = false)
            } catch (e: CancellationException) {
                // 用户主动取消 → 静默回到 Idle
                _state.value = SettingsUiState.Idle
                throw e
            } catch (e: BackupException) {
                val message = errorMessage(R.string.settings_result_export_failed, e.message ?: "未知原因")
                finalize(message = message, isError = true)
            } catch (e: Exception) {
                val message = errorMessage(R.string.settings_result_export_failed, e.message ?: "未知错误")
                finalize(message = message, isError = true)
            }
        }
    }

    /**
     * 导入。失败弹全局 Snackbar（isError=true → ⚠️ 前缀），UI 转 Done 等用户 acknowledge。
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
                finalize(message = message, isError = false)
            } catch (e: CancellationException) {
                _state.value = SettingsUiState.Idle
                throw e
            } catch (e: BackupException) {
                val message = errorMessage(R.string.settings_result_import_failed, e.message ?: "未知原因")
                finalize(message = message, isError = true)
            } catch (e: Exception) {
                val message = errorMessage(R.string.settings_result_import_failed, e.message ?: "未知错误")
                finalize(message = message, isError = true)
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

    /**
     * 完成收尾：state 置 Done + emit 事件到全局 Snackbar。
     *
     * 集中一处避免每个 catch 块重复同样的 `_state.value = Done; emitDone(event)`。
     */
    private suspend fun finalize(
        message: String,
        isError: Boolean,
    ) {
        _state.value = SettingsUiState.Done
        settingsEventsSink.emitSettings(SettingsDoneEvent(message = message, isError = isError))
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
     *
     * **P3-3 修**：分隔符「；」从硬编码搬到 `settings_result_extras_separator` 资源，多语言版
     * 本可替换。
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
        val separator = app.getString(R.string.settings_result_extras_separator)
        return if (extras.isEmpty()) main else "$main（${extras.joinToString(separator)}）"
    }
}