package com.shuaji.cards.ui.screen

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.R
import com.shuaji.cards.core.AppLocales
import com.shuaji.cards.data.SettingsDoneEvent
import com.shuaji.cards.data.ThemeSettings
import com.shuaji.cards.data.backup.BackupCancelResult
import com.shuaji.cards.data.backup.BackupException
import com.shuaji.cards.data.backup.BackupPreview
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface SettingsUiState {
    data object Idle : SettingsUiState

    data object Working : SettingsUiState

    data object Done : SettingsUiState
}

data class PendingImport(
    val uri: Uri,
    val info: BackupPreview,
)

class SettingsViewModel(
    application: Application,
    private val backup: BackupRepository,
    private val emitSettingsEvent: suspend (SettingsDoneEvent) -> Unit,
    private val settingsRepo: com.shuaji.cards.data.SettingsRepository,
    private val reminderStore: com.shuaji.cards.data.reminder.AnnualFeeReminderStore,
    private val requestReminderReschedule: () -> Unit = {},
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    private val _pendingReminderPermissionRequest = MutableStateFlow(false)
    val pendingReminderPermissionRequest: StateFlow<Boolean> =
        _pendingReminderPermissionRequest.asStateFlow()

    private val operationLock = ReentrantLock()
    private var operationJob: Job? = null

    val themeSettings: StateFlow<ThemeSettings?> =
        settingsRepo.themeSettings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    val annualFeeRemindersEnabled: StateFlow<Boolean> =
        reminderStore.observeEnabled().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = reminderStore.isEnabled(),
        )

    fun setAnnualFeeRemindersEnabled(enabled: Boolean) {
        reminderStore.setEnabled(enabled)
    }

    fun hasAskedNotificationPermission(): Boolean = reminderStore.hasAskedNotificationPermission()

    fun markAskedNotificationPermission() {
        reminderStore.markAskedNotificationPermission()
    }

    fun onNotificationPermissionMaybeChanged() {
        requestReminderReschedule()
    }

    fun acknowledgeReminderPermissionRequest() {
        _pendingReminderPermissionRequest.value = false
    }

    fun setThemeMode(mode: com.shuaji.cards.data.ThemeMode) {
        launchAppearanceWrite { settingsRepo.setThemeMode(mode) }
    }

    fun setColorSource(source: com.shuaji.cards.data.ColorSource) {
        launchAppearanceWrite { settingsRepo.setColorSource(source) }
    }

    fun setSeedColorHex(hex: String?) {
        launchAppearanceWrite { settingsRepo.setSeedColorHex(hex) }
    }

    private fun launchAppearanceWrite(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: IOException) {
                emitSettingsEvent(
                    SettingsDoneEvent(
                        message =
                            AppLocales
                                .localize(
                                    getApplication<Application>(),
                                ).getString(R.string.settings_result_appearance_save_failed),
                        isError = true,
                    ),
                )
            }
        }
    }

    fun inspectBackup(uri: Uri) {
        launchOperation { job ->
            try {
                _pendingImport.value = PendingImport(uri, backup.inspect(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finalize(job, backupErrorMessage(R.string.settings_result_inspect_failed, e), isError = true)
            }
        }
    }

    fun export(uri: Uri) {
        launchOperation { job ->
            try {
                val summary: ExportSummary = backup.export(uri)
                val app = AppLocales.localize(getApplication<Application>())
                val resultMessage =
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
                val message =
                    app.getString(
                        R.string.settings_result_export_directory,
                        resultMessage,
                        summary.directoryName,
                    )
                finalize(job, message = message, isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finalize(job, backupErrorMessage(R.string.settings_result_export_failed, e), isError = true)
            }
        }
    }

    fun import(
        uri: Uri,
        mode: ImportMode,
        expectedManifestSha256: String,
    ) {
        launchOperation { job ->
            try {
                val result = backup.import(uri, mode, expectedManifestSha256)
                if (result.annualFeeRemindersEnabled) {
                    _pendingReminderPermissionRequest.value = true
                }
                val message = formatImportMessage(result, mode)
                finalize(job, message = message, isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finalize(job, backupErrorMessage(R.string.settings_result_import_failed, e), isError = true)
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
            expectedManifestSha256 = pending.info.manifestSha256,
        )
    }

    fun cancel() {
        val job = operationLock.withLock { operationJob }
        when (backup.cancelActive()) {
            BackupCancelResult.CANCELLED -> job?.cancel(CancellationException("User cancelled settings operation"))
            BackupCancelResult.COMMIT_IN_PROGRESS -> Unit
            BackupCancelResult.NO_ACTIVE_OPERATION -> {
                viewModelScope.launch {
                    yield()
                    if (backup.cancelActive() == BackupCancelResult.CANCELLED) {
                        job?.cancel(CancellationException("User cancelled settings operation"))
                    }
                }
            }
        }
    }

    fun acknowledge() {
        _state.compareAndSet(SettingsUiState.Done, SettingsUiState.Idle)
    }

    private suspend fun finalize(
        owner: Job,
        message: String,
        isError: Boolean,
    ) {
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

    private fun backupErrorMessage(
        @androidx.annotation.StringRes templateRes: Int,
        error: Exception,
    ): String {
        val app = AppLocales.localize(getApplication<Application>())
        val reason = (error as? BackupException)?.message ?: app.getString(R.string.common_unknown_reason)
        return app.getString(templateRes, reason)
    }

    private fun formatImportMessage(
        result: ImportResult,
        mode: ImportMode,
    ): String {
        val app = AppLocales.localize(getApplication<Application>())
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
            }
        val separator = app.getString(R.string.settings_result_extras_separator)
        return if (extras.isEmpty()) main else "$main（${extras.joinToString(separator)}）"
    }

    private fun Context.quantityString(
        @PluralsRes resourceId: Int,
        count: Int,
    ): String = resources.getQuantityString(resourceId, count, count)
}
