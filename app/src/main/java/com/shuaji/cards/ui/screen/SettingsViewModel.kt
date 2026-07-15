package com.shuaji.cards.ui.screen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.R
import com.shuaji.cards.data.AppContainer
import com.shuaji.cards.data.SettingsDoneEvent
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
 * [settingsEventsSink] 是必需依赖，由 [com.shuaji.cards.ui.ViewModelFactories.Settings] 注入。
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
            } catch (e: Exception) {
                // BackupException 与其它异常统一处理；错误文案已国际化（见 BackupRepository / errorMessage）
                finalize(errorMessage(R.string.settings_result_export_failed, e.message), isError = true)
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
            } catch (e: Exception) {
                finalize(errorMessage(R.string.settings_result_import_failed, e.message), isError = true)
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
     * SettingsScreen 观察到完成态后调用，仅重置页面状态；
     * Snackbar 事件已独立发布到 [settingsEventsSink]。
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

    /** 错误文案统一封装：「XXX 失败：<cause>」。cause 为 null（异常无 message）时回退到本地化的「未知原因」。 */
    private fun errorMessage(
        @androidx.annotation.StringRes templateRes: Int,
        cause: String?,
    ): String {
        val app = getApplication<Application>()
        return app.getString(templateRes, cause ?: app.getString(R.string.common_unknown_reason))
    }

    /**
     * 把 [ImportResult] 拼成一行可读消息。
     *
     * 拼接顺序：模式前缀 → 主体（卡/文件夹/流水）→ 副提示（跳过/重名/FK 校验失败 / 卡面 URI 跨设备失效）。
     * 任何副提示为 0 就不出现对应字段，让消息保持简洁。
     *
     * 所有用户可见文案和分隔符都从字符串资源获取。
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
