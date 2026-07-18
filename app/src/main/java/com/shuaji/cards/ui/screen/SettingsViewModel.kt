package com.shuaji.cards.ui.screen

import android.app.Application
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.R
import com.shuaji.cards.data.SettingsDoneEvent
import com.shuaji.cards.data.backup.BackupCancelResult
import com.shuaji.cards.data.backup.BackupFileInfo
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.backup.ExportSummary
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.data.backup.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

data class PendingImport(
    val uri: Uri,
    val info: BackupFileInfo,
)

/**
 * SettingsViewModel。
 *
 * 继承 [AndroidViewModel] 只为通过 Application 解析带参数的本地化结果文案，
 * 不持有 Activity 或 Composable Context。
 *
 * 结果发布只依赖窄的 suspend 函数，不让 ViewModel 反向依赖整个应用容器。
 */
class SettingsViewModel(
    application: Application,
    private val backup: BackupRepository,
    private val emitSettingsEvent: suspend (SettingsDoneEvent) -> Unit,
    private val settingsRepo: com.shuaji.cards.data.SettingsRepository,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()
    private val operationLock = ReentrantLock()
    private var operationJob: Job? = null

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
     * 用仓库的正式解码路径检查待导入文件；成功才允许 UI 进入模式确认对话框。
     * 失败通过全局 Snackbar 告知原因，避免把损坏文件伪装成“0 条记录”。
     */
    suspend fun inspectBackup(uri: Uri): BackupFileInfo? {
        val job = checkNotNull(currentCoroutineContext()[Job])
        if (!registerOperation(job)) return null
        return try {
            backup.inspect(uri).also { info ->
                _pendingImport.value = PendingImport(uri, info)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finalize(job, errorMessage(R.string.settings_result_inspect_failed, e.message), isError = true)
            null
        } finally {
            releaseOperation(job)
        }
    }

    /**
     * 导出。失败弹全局 Snackbar（isError=true → ⚠️ 前缀），UI 转 Done 后自动回到 Idle。
     * 协程被取消（CancellationException）→ 静默回到 Idle，不弹错误 Snackbar。
     */
    fun export(uri: Uri) {
        launchOperation { job ->
            try {
                val summary: ExportSummary = backup.export(uri)
                val app = getApplication<Application>()
                val message =
                    if (summary.isEmpty) {
                        app.getString(R.string.settings_result_export_empty)
                    } else {
                        app.getString(
                            R.string.settings_result_export_success_with_count,
                            app.quantityString(R.plurals.backup_count_cards, summary.cardCount),
                            app.quantityString(R.plurals.backup_count_folders, summary.folderCount),
                            app.quantityString(R.plurals.backup_count_transactions, summary.transactionCount),
                        )
                    }
                finalize(job, message = message, isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // BackupException 与其它异常统一处理；错误文案已国际化（见 BackupRepository / errorMessage）
                finalize(job, errorMessage(R.string.settings_result_export_failed, e.message), isError = true)
            }
        }
    }

    /**
     * 导入。失败弹全局 Snackbar（isError=true → ⚠️ 前缀），UI 转 Done 后自动回到 Idle。
     * 协程被取消（CancellationException）→ 静默回到 Idle，不弹错误 Snackbar。
     */
    fun import(
        uri: Uri,
        mode: ImportMode,
        expectedContentSha256: String,
    ) {
        launchOperation { job ->
            try {
                val result = backup.import(uri, mode, expectedContentSha256)
                val message = formatImportMessage(result, mode)
                finalize(job, message = message, isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finalize(job, errorMessage(R.string.settings_result_import_failed, e.message), isError = true)
            }
        }
    }

    fun dismissPendingImport() {
        _pendingImport.value = null
    }

    fun importPending(mode: ImportMode) {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        import(
            uri = pending.uri,
            mode = mode,
            expectedContentSha256 = pending.info.contentSha256,
        )
    }

    /**
     * 取消当前正在跑的导入 / 导出。
     *
     * 读取/校验阶段由 [BackupRepository.cancelActive] 关闭流并取消所属 Job；若导入已经进入
     * 不可取消的提交边界，则保持 Working，等待事务和授权收尾给出确定回执。
     */
    fun cancel() {
        val job = operationLock.withLock { operationJob }
        when (backup.cancelActive()) {
            BackupCancelResult.CANCELLED -> job?.cancel(CancellationException("User cancelled settings operation"))
            BackupCancelResult.COMMIT_IN_PROGRESS -> Unit
            BackupCancelResult.NO_ACTIVE_OPERATION -> {
                // 可能是 Job 刚启动、仓库尚未来得及登记；让出一轮后只重试仓库取消。
                // 若这是仓库已返回但 ViewModel 尚未 finalize 的极短窗口，绝不能取消 Job，
                // 否则数据库已经提交却不会发布成功回执。
                viewModelScope.launch {
                    yield()
                    if (backup.cancelActive() == BackupCancelResult.CANCELLED) {
                        job?.cancel(CancellationException("User cancelled settings operation"))
                    }
                }
            }
        }
    }

    /**
     * SettingsScreen 观察到完成态后调用，仅重置页面状态；
     * Snackbar 事件已通过 [emitSettingsEvent] 独立发布。
     */
    fun acknowledge() {
        _state.compareAndSet(SettingsUiState.Done, SettingsUiState.Idle)
    }

    /**
     * 完成收尾：state 置 Done + emit 事件到全局 Snackbar。
     *
     * 集中一处避免每个 catch 块重复同样的 `_state.value = Done; emitDone(event)`。
     */
    private suspend fun finalize(
        owner: Job,
        message: String,
        isError: Boolean,
    ) {
        // 取消是终态：即使底层 Provider 随后返回普通结果/异常，也不能再发布成功或失败回执。
        currentCoroutineContext().ensureActive()
        val stillOwner =
            operationLock.withLock {
                if (operationJob !== owner) return@withLock false
                _state.value = SettingsUiState.Done
                true
            }
        if (!stillOwner) return
        emitSettingsEvent(SettingsDoneEvent(message = message, isError = isError))
    }

    /** 同一时间只允许一个预览、导入或导出操作拥有页面状态。 */
    private fun registerOperation(job: Job): Boolean =
        operationLock.withLock {
            if (operationJob != null || _state.value !is SettingsUiState.Idle) return@withLock false
            operationJob = job
            _state.value = SettingsUiState.Working
            true
        }

    private fun releaseOperation(job: Job) {
        operationLock.withLock {
            if (operationJob !== job) return
            operationJob = null
            _state.compareAndSet(SettingsUiState.Working, SettingsUiState.Idle)
        }
    }

    private fun launchOperation(block: suspend (Job) -> Unit) {
        lateinit var job: Job
        job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    block(checkNotNull(currentCoroutineContext()[Job]))
                } finally {
                    releaseOperation(job)
                }
            }
        if (registerOperation(job)) {
            job.start()
        } else {
            job.cancel()
        }
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
        val main =
            app.getString(
                mainResId,
                app.quantityString(R.plurals.backup_count_cards, result.cardsAdded),
                app.quantityString(R.plurals.backup_count_folders, result.foldersAdded),
                app.quantityString(R.plurals.backup_count_transactions, result.transactionsAdded),
            )
        val extras =
            buildList {
                if (result.transactionsSkipped > 0) {
                    add(
                        app.quantityString(
                            R.plurals.settings_result_extras_transactions_skipped,
                            result.transactionsSkipped,
                        ),
                    )
                }
                if (result.cardsSkippedInvalidFolder > 0) {
                    add(
                        app.quantityString(
                            R.plurals.settings_result_extras_cards_invalid_folder,
                            result.cardsSkippedInvalidFolder,
                        ),
                    )
                }
                if (result.duplicateFolderNames > 0) {
                    add(
                        app.quantityString(
                            R.plurals.settings_result_extras_duplicate_folders,
                            result.duplicateFolderNames,
                        ),
                    )
                }
                if (result.duplicateCardNames > 0) {
                    add(
                        app.quantityString(
                            R.plurals.settings_result_extras_duplicate_cards,
                            result.duplicateCardNames,
                        ),
                    )
                }
                if (result.imageUriUserCount > 0) {
                    add(
                        app.quantityString(
                            R.plurals.settings_result_image_uri_potentially_broken,
                            result.imageUriUserCount,
                        ),
                    )
                }
            }
        val separator = app.getString(R.string.settings_result_extras_separator)
        return if (extras.isEmpty()) main else "$main（${extras.joinToString(separator)}）"
    }

    private fun Application.quantityString(
        @PluralsRes resourceId: Int,
        count: Int,
    ): String = resources.getQuantityString(resourceId, count, count)
}
