package com.shuaji.cards.ui.screen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.SettingsDoneEvent
import com.shuaji.cards.data.SettingsRepository
import com.shuaji.cards.data.ThemeSettings
import com.shuaji.cards.data.backup.BackupCancelResult
import com.shuaji.cards.data.backup.BackupException
import com.shuaji.cards.data.backup.BackupPreview
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.backup.ExportSummary
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.data.backup.ImportResult
import com.shuaji.cards.data.reminder.AnnualFeeReminderStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

private const val TEST_CONTENT_SHA256 = "test-content-sha256"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var application: Application
    private lateinit var backup: BackupRepository
    private lateinit var reminderStore: AnnualFeeReminderStore

    private val settingsRepo: SettingsRepository =
        mock { on { themeSettings } doReturn flowOf(ThemeSettings()) }

    private val emittedEvents = mutableListOf<SettingsDoneEvent>()

    private var onEmitSettings: ((SettingsDoneEvent) -> Unit)? = null
    private val emitSettingsEvent: suspend (SettingsDoneEvent) -> Unit = { event ->
        onEmitSettings?.invoke(event)
        emittedEvents += event
    }

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        reminderStore = AnnualFeeReminderStore(application)
        backup = mock()
        whenever(backup.cancelActive()).doReturn(BackupCancelResult.CANCELLED)
    }

    private fun newVm() = SettingsViewModel(application, backup, emitSettingsEvent, settingsRepo, reminderStore)

    private fun TestScope.runAndCollect(action: () -> Unit): List<SettingsDoneEvent> {
        val before = emittedEvents.size
        action()
        advanceUntilIdle()
        return emittedEvents.drop(before)
    }

    @Test
    fun export_success_emits_event_with_export_message() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doReturn(
                ExportSummary(
                    cardCount = 5,
                    folderCount = 2,
                    transactionCount = 12,
                    directoryName = "CardRecord_Backup_20260722_120000",
                ),
            )

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.export(android.net.Uri.EMPTY)
                }

            assertEquals("应收到 1 条 emit", 1, collected.size)
            val message = collected[0].message
            assertTrue("消息应含「已导出」，实际：$message", message.contains("已导出"))
            assertTrue("消息应含「5 张卡」，实际：$message", message.contains("5 张卡"))
            assertTrue("消息应含「2 个文件夹」，实际：$message", message.contains("2 个文件夹"))
            assertTrue("消息应含「12 笔流水」，实际：$message", message.contains("12 笔流水"))
            assertTrue("消息应含实际目录名，实际：$message", message.contains("CardRecord_Backup_20260722_120000"))
            assertFalse("成功事件 isError=false", collected[0].isError)
        }

    @Test
    fun export_empty_db_uses_empty_message() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doReturn(ExportSummary(0, 0, 0, "test-backup"))

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.export(android.net.Uri.EMPTY)
                }

            assertEquals(1, collected.size)
            assertTrue(
                "空库应使用「已创建空备份目录」文案，实际：${collected[0].message}",
                collected[0].message.contains("已创建空备份目录"),
            )
        }

    @Test
    fun export_failure_marks_event_as_error() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doThrow(BackupException("磁盘满"))

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.export(android.net.Uri.EMPTY)
                }

            assertEquals(1, collected.size)
            assertTrue("失败应标记 isError=true", collected[0].isError)
            assertTrue(
                "失败消息应含原因，实际：${collected[0].message}",
                collected[0].message.contains("磁盘满"),
            )
        }

    @Test
    fun unexpectedFailure_doesNotExposeInternalExceptionMessage() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doThrow(IllegalStateException("internal-secret-detail"))

            val collected = runAndCollect { newVm().export(android.net.Uri.EMPTY) }

            assertEquals(1, collected.size)
            assertTrue(collected.single().isError)
            assertFalse(collected.single().message.contains("internal-secret-detail"))
            assertTrue(collected.single().message.contains("未知原因"))
        }

    @Test
    fun merge_import_success_formats_counts() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any(), any())).doReturn(
                ImportResult(
                    cardsAdded = 3,
                    foldersAdded = 1,
                    transactionsAdded = 0,
                ),
            )

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.import(android.net.Uri.EMPTY, ImportMode.MERGE, TEST_CONTENT_SHA256)
                }

            assertEquals(1, collected.size)
            val msg = collected[0].message
            assertTrue("消息应含「追加成功」，实际：$msg", msg.contains("追加成功"))
            assertTrue("消息应含「3 张卡」，实际：$msg", msg.contains("3 张卡"))
        }

    @Test
    fun state_transitions_Idle_Working_Done_then_acknowledge_to_Idle() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val vm = newVm()
            whenever(backup.export(any())).doAnswer {
                assertEquals("export 执行期间应为 Working", SettingsUiState.Working, vm.state.value)
                ExportSummary(cardCount = 1, folderCount = 0, transactionCount = 0, directoryName = "test-backup")
            }
            assertEquals("初始应为 Idle", SettingsUiState.Idle, vm.state.value)

            val collected = runAndCollect { vm.export(android.net.Uri.EMPTY) }

            assertEquals("export 完成后应为 Done", SettingsUiState.Done, vm.state.value)
            assertEquals(1, collected.size)

            vm.acknowledge()
            assertEquals("acknowledge 后回到 Idle", SettingsUiState.Idle, vm.state.value)
        }

    @Test
    fun import_failure_marks_event_as_error_with_cause() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any(), any())).doThrow(BackupException("版本不匹配"))

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.import(android.net.Uri.EMPTY, ImportMode.REPLACE, TEST_CONTENT_SHA256)
                }

            assertEquals(1, collected.size)
            assertTrue("失败应标记 isError=true", collected[0].isError)
            assertTrue(
                "失败消息应含「版本不匹配」，实际：${collected[0].message}",
                collected[0].message.contains("版本不匹配"),
            )
        }

    @Test
    fun import_withRemindersEnabledInBackup_requestsNotificationPermission() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any(), any())).doReturn(
                ImportResult(
                    cardsAdded = 1,
                    foldersAdded = 0,
                    transactionsAdded = 0,
                    annualFeeRemindersEnabled = true,
                ),
            )
            val vm = newVm()

            runAndCollect { vm.import(android.net.Uri.EMPTY, ImportMode.REPLACE, TEST_CONTENT_SHA256) }

            assertTrue(vm.pendingReminderPermissionRequest.value)
            vm.acknowledgeReminderPermissionRequest()
            assertFalse(vm.pendingReminderPermissionRequest.value)
        }

    @Test
    fun import_withRemindersDisabledInBackup_doesNotRequestPermission() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any(), any())).doReturn(
                ImportResult(
                    cardsAdded = 1,
                    foldersAdded = 0,
                    transactionsAdded = 0,
                ),
            )
            val vm = newVm()

            runAndCollect { vm.import(android.net.Uri.EMPTY, ImportMode.MERGE, TEST_CONTENT_SHA256) }

            assertFalse(vm.pendingReminderPermissionRequest.value)
        }

    @Test
    fun inspect_success_storesTrustedPreview_andRestoresIdle() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/backup")
            val expected = BackupPreview(2, 1, 3, 123L, "preview-sha256")
            whenever(backup.inspect(uri)).doReturn(expected)
            val vm = newVm()

            val events = runAndCollect { vm.inspectBackup(uri) }

            assertTrue(events.isEmpty())
            assertEquals(SettingsUiState.Idle, vm.state.value)
            assertEquals(PendingImport(uri, expected), vm.pendingImport.value)
        }

    @Test
    fun appearanceWriteIOException_emitsErrorReceiptInsteadOfCrashing() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(settingsRepo.setThemeMode(any())).doSuspendableAnswer { throw IOException("disk full") }
            val vm = newVm()

            val events = runAndCollect { vm.setThemeMode(com.shuaji.cards.data.ThemeMode.DARK) }

            assertEquals(1, events.size)
            assertTrue(events.single().isError)
        }

    @Test
    fun inspect_failure_emitsErrorReceipt_withoutPendingImport() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/broken")
            whenever(backup.inspect(uri)).doThrow(IllegalStateException("broken"))
            val vm = newVm()

            val events = runAndCollect { vm.inspectBackup(uri) }

            assertEquals(1, events.size)
            assertTrue(events.single().isError)
            assertEquals(null, vm.pendingImport.value)
        }

    @Test
    fun import_forwardsPreviewDigestToRepository() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/success")
            whenever(backup.import(uri, ImportMode.MERGE, "preview-sha256")).doReturn(ImportResult(1, 0, 0))

            runAndCollect { newVm().import(uri, ImportMode.MERGE, "preview-sha256") }

            verify(backup).import(uri, ImportMode.MERGE, "preview-sha256")
        }

    @Test
    fun importPending_usesAtomicUriAndDigestThenClearsConfirmation() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/pending")
            val info = BackupPreview(2, 1, 3, null, "pending-sha256")
            whenever(backup.inspect(uri)).doReturn(info)
            whenever(backup.import(uri, ImportMode.REPLACE, info.manifestSha256)).doReturn(ImportResult(2, 1, 3))
            val vm = newVm()
            runAndCollect { vm.inspectBackup(uri) }

            runAndCollect { vm.importPending(ImportMode.REPLACE) }

            assertEquals(null, vm.pendingImport.value)
            verify(backup).import(uri, ImportMode.REPLACE, info.manifestSha256)
        }

    @Test
    fun cancel_active_export_does_not_emit_done_event() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doAnswer {
                throw kotlinx.coroutines.CancellationException("cancelled by user")
            }

            val vm = newVm()
            val collected = runAndCollect { vm.export(android.net.Uri.EMPTY) }

            assertTrue("cancel 不应 emit Done 事件，实际：$collected", collected.isEmpty())
            assertEquals(SettingsUiState.Idle, vm.state.value)
        }

    @Test
    fun rapidDuplicateExport_startsOnlyOneOperation() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/export")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            whenever(backup.export(uri)).doSuspendableAnswer {
                entered.complete(Unit)
                release.await()
                ExportSummary(1, 0, 0, "test-backup")
            }
            val vm = newVm()

            vm.export(uri)
            runCurrent()
            entered.await()
            vm.export(uri)
            runCurrent()

            verify(backup, times(1)).export(uri)
            assertEquals(SettingsUiState.Working, vm.state.value)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(SettingsUiState.Done, vm.state.value)
        }

    @Test
    fun cancel_cancelsOwningJobAndReturnsIdle() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/export")
            whenever(backup.export(uri)).doSuspendableAnswer { awaitCancellation() }
            val vm = newVm()

            vm.export(uri)
            runCurrent()
            assertEquals(SettingsUiState.Working, vm.state.value)

            vm.cancel()
            advanceUntilIdle()

            assertEquals(SettingsUiState.Idle, vm.state.value)
            verify(backup).cancelActive()
        }

    @Test
    fun cancel_nonCooperativeProviderResultStillDoesNotEmitDone() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/non-cooperative")
            val entered = CompletableDeferred<Unit>()
            whenever(backup.export(uri)).doSuspendableAnswer {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: kotlinx.coroutines.CancellationException) {
                }
                ExportSummary(1, 0, 0, "test-backup")
            }
            val vm = newVm()

            vm.export(uri)
            runCurrent()
            entered.await()
            vm.cancel()
            advanceUntilIdle()

            assertEquals(SettingsUiState.Idle, vm.state.value)
            assertTrue(emittedEvents.isEmpty())
        }

    @Test
    fun cancel_duringCommit_waitsForDefinitiveResult() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/committing")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            whenever(backup.import(uri, ImportMode.REPLACE, TEST_CONTENT_SHA256)).doSuspendableAnswer {
                entered.complete(Unit)
                release.await()
                ImportResult(1, 0, 0)
            }
            whenever(backup.cancelActive()).doReturn(BackupCancelResult.COMMIT_IN_PROGRESS)
            val vm = newVm()

            vm.import(uri, ImportMode.REPLACE, TEST_CONTENT_SHA256)
            runCurrent()
            entered.await()
            vm.cancel()
            runCurrent()

            assertEquals(SettingsUiState.Working, vm.state.value)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(SettingsUiState.Done, vm.state.value)
            assertEquals(1, emittedEvents.size)
        }

    @Test
    fun cancel_withNoRegisteredRepositoryOperation_doesNotHideLaterSuccess() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val uri = android.net.Uri.parse("content://test/no-active-race")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            whenever(backup.export(uri)).doSuspendableAnswer {
                entered.complete(Unit)
                release.await()
                ExportSummary(1, 0, 0, "test-backup")
            }
            whenever(backup.cancelActive()).doReturn(BackupCancelResult.NO_ACTIVE_OPERATION)
            val vm = newVm()

            vm.export(uri)
            runCurrent()
            entered.await()
            vm.cancel()
            runCurrent()

            assertEquals(SettingsUiState.Working, vm.state.value)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(SettingsUiState.Done, vm.state.value)
            assertEquals(1, emittedEvents.size)
        }

    @Test
    fun replace_import_success_formats_counts() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any(), any())).doReturn(
                ImportResult(
                    cardsAdded = 5,
                    foldersAdded = 2,
                    transactionsAdded = 10,
                ),
            )

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.import(android.net.Uri.EMPTY, ImportMode.REPLACE, TEST_CONTENT_SHA256)
                }

            assertEquals(1, collected.size)
            val msg = collected[0].message
            assertTrue("REPLACE 消息应含「覆盖成功」，实际：$msg", msg.contains("覆盖成功"))
            assertTrue("消息应含「5 张卡」，实际：$msg", msg.contains("5 张卡"))
            assertTrue("消息应含「2 个文件夹」，实际：$msg", msg.contains("2 个文件夹"))
            assertTrue("消息应含「10 笔流水」，实际：$msg", msg.contains("10 笔流水"))
        }

    @Test
    fun merge_import_with_all_extras_triggers_all_submessages() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any(), any())).doReturn(
                ImportResult(
                    cardsAdded = 8,
                    foldersAdded = 3,
                    transactionsAdded = 20,
                    transactionsSkipped = 2,
                    cardsSkippedInvalidFolder = 1,
                    duplicateFolderNames = 1,
                    duplicateCardNames = 2,
                ),
            )

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.import(android.net.Uri.EMPTY, ImportMode.MERGE, TEST_CONTENT_SHA256)
                }

            assertEquals(1, collected.size)
            val msg = collected[0].message
            assertTrue("MERGE 消息应含「追加成功」，实际：$msg", msg.contains("追加成功"))
            assertTrue("应含「8 张卡」，实际：$msg", msg.contains("8 张卡"))
            assertTrue("应含「3 个文件夹」，实际：$msg", msg.contains("3 个文件夹"))
            assertTrue("应含「20 笔流水」，实际：$msg", msg.contains("20 笔流水"))
            assertTrue(
                "transactionsSkipped=2 应触发「2 笔流水...被跳过」，实际：$msg",
                msg.contains("2 笔流水") && msg.contains("跳过"),
            )
            assertTrue(
                "cardsSkippedInvalidFolder=1 应触发「1 张卡...未分类」，实际：$msg",
                msg.contains("1 张卡") && msg.contains("未分类"),
            )
            assertTrue(
                "duplicateFolderNames=1 应触发「1 个文件夹...重名」，实际：$msg",
                msg.contains("1 个文件夹") && msg.contains("重名"),
            )
            assertTrue(
                "duplicateCardNames=2 应触发「2 张卡...重名」，实际：$msg",
                msg.contains("2 张卡") && msg.contains("重名"),
            )
        }

    @Test
    fun finalize_writes_done_state_and_emits_exactly_once() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doReturn(
                ExportSummary(cardCount = 1, folderCount = 0, transactionCount = 0, directoryName = "test-backup"),
            )

            val vm = newVm()
            onEmitSettings = {
                assertEquals("emitSettings 时 state 应已是 Done", SettingsUiState.Done, vm.state.value)
            }
            val collected = runAndCollect { vm.export(android.net.Uri.EMPTY) }

            assertEquals(1, collected.size)
            assertEquals(SettingsUiState.Done, vm.state.value)
        }

    @Test
    fun annual_fee_reminders_enabled_reflects_store_before_any_collection() {
        reminderStore.setEnabled(true)

        assertTrue(newVm().annualFeeRemindersEnabled.value)
    }
}
