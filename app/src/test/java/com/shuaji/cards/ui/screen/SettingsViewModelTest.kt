package com.shuaji.cards.ui.screen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.AppContainer
import com.shuaji.cards.data.SettingsDoneEvent
import com.shuaji.cards.data.SettingsRepository
import com.shuaji.cards.data.ThemeSettings
import com.shuaji.cards.data.backup.BackupException
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.backup.ExportSummary
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.data.backup.ImportResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SettingsViewModel] 的核心行为测试。
 *
 * 测试策略：
 * - 用 Robolectric 拿真实 [Application]，让 `getString(R.string.xxx, args...)` 走真实
 *   Android 资源系统（验证国际化字符串拼装正确）。
 * - 用 mockito-kotlin mock 掉 [BackupRepository] / [SettingsRepository]，聚焦 ViewModel
 *   自身的状态机与「是否把正确的 [SettingsDoneEvent] 推给了 [AppContainer]」。
 * - [fakeContainer] 直接捕获 `emitSettings` 的入参到 [emittedEvents]——ViewModel 测试只关心
 *   「有没有发、发了什么」，SharedFlow 的 replay / 订阅时序语义属于 AppContainer 的职责，
 *   不该耦合进来，否则测试会被并发时序拖得既脆弱又难懂。
 * - 关键：[runTest] 复用 [MainDispatcherRule] 的 scheduler，`advanceUntilIdle()` 才能驱动
 *   ViewModel `viewModelScope.launch` 里的协程跑完。
 *
 * 状态机约定：export / import 成功 → `Working → Done`，并 emit 一条事件；`Done` 由 UI
 * （SettingsScreen）看到后调 [SettingsViewModel.acknowledge] 回 `Idle`。协程被取消
 * （CancellationException）→ 静默回 `Idle`，不 emit。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var application: Application
    private lateinit var backup: BackupRepository

    /** 产线 SettingsViewModel 构造即读取 themeSettings；测试只需 stub 出一个非空流。 */
    private val settingsRepo: SettingsRepository =
        mock { on { themeSettings } doReturn flowOf(ThemeSettings()) }

    /** fakeContainer 把 ViewModel emit 的事件直接记到这里，断言时读取即可。 */
    private val emittedEvents = mutableListOf<SettingsDoneEvent>()

    /** 可选钩子：emitSettings 被调用的「那一刻」同步触发，用于断言 emit 时点的状态。 */
    private var onEmitSettings: ((SettingsDoneEvent) -> Unit)? = null
    private val fakeContainer =
        object : AppContainer {
            // 没测的 repo / settings 留空即可——用 `by lazy` 包一层，**仅在访问时才抛** error()。
            // Kotlin object 初始值是立即求值，普通 `= error("not used")` 会让 fakeContainer
            // 一被构造就 ISE 把所有测试带挂。
            override val repository: com.shuaji.cards.data.CardRepository by lazy { error("not used") }
            override val settings: com.shuaji.cards.data.SettingsRepository by lazy { error("not used") }
            override val backup: BackupRepository by lazy { error("not used in fake") }
            override val cycleAutoResetEvents: SharedFlow<Int> by lazy { error("not used") }
            override val settingsEvents: SharedFlow<SettingsDoneEvent> =
                MutableSharedFlow<SettingsDoneEvent>().asSharedFlow()

            override suspend fun emitSettings(event: SettingsDoneEvent) {
                onEmitSettings?.invoke(event)
                emittedEvents += event
            }

            // 测试不涉及启动续期，留空实现满足接口即可。
            override suspend fun runStartupCycleReset(nowMillis: Long) = Unit
        }

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        backup = mock()
    }

    private fun newVm() = SettingsViewModel(application, backup, fakeContainer, settingsRepo)

    /** 跑一段触发 ViewModel 的动作，推进到协程空闲，返回这期间新 emit 的事件快照。 */
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
                ExportSummary(cardCount = 5, folderCount = 2, transactionCount = 12),
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
            assertFalse("成功事件 isError=false", collected[0].isError)
        }

    @Test
    fun export_empty_db_uses_empty_message() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doReturn(ExportSummary(0, 0, 0))

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.export(android.net.Uri.EMPTY)
                }

            assertEquals(1, collected.size)
            assertTrue(
                "空库应使用「已导出空备份」文案，实际：${collected[0].message}",
                collected[0].message.contains("已导出空备份"),
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
    fun import_with_imageUriUserCount_includes_image_warning() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any())).doReturn(
                ImportResult(
                    cardsAdded = 3,
                    foldersAdded = 1,
                    transactionsAdded = 0,
                    imageUriUserCount = 2,
                ),
            )

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.import(android.net.Uri.EMPTY, ImportMode.MERGE)
                }

            assertEquals(1, collected.size)
            val msg = collected[0].message
            assertTrue("消息应含「3 张卡」，实际：$msg", msg.contains("3 张卡"))
            assertTrue(
                "imageUriUserCount=2 应触发「2 张卡...重新上传」，实际：$msg",
                msg.contains("2 张卡") && msg.contains("重新上传"),
            )
        }

    // ════════════════════════════════════════════════════════════
    // 状态机
    // ════════════════════════════════════════════════════════════

    @Test
    fun state_transitions_Idle_Working_Done_then_acknowledge_to_Idle() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val vm = newVm()
            // 用 doAnswer 在 export 真正执行期间断言已进入 Working——仅看最终态无法证明中间态。
            whenever(backup.export(any())).doAnswer {
                assertEquals("export 执行期间应为 Working", SettingsUiState.Working, vm.state.value)
                ExportSummary(cardCount = 1, folderCount = 0, transactionCount = 0)
            }
            assertEquals("初始应为 Idle", SettingsUiState.Idle, vm.state.value)

            val collected = runAndCollect { vm.export(android.net.Uri.EMPTY) }

            // export 完成：Working → Done，并 emit 一条事件。产线下由 SettingsScreen 看到 Done
            // 后调 acknowledge() 回 Idle；测试里没有 UI，故手动 acknowledge() 验证完整流转。
            assertEquals("export 完成后应为 Done", SettingsUiState.Done, vm.state.value)
            assertEquals(1, collected.size)

            vm.acknowledge()
            assertEquals("acknowledge 后回到 Idle", SettingsUiState.Idle, vm.state.value)
        }

    @Test
    fun acknowledge_is_idempotent_and_does_not_re_emit() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doReturn(
                ExportSummary(cardCount = 1, folderCount = 0, transactionCount = 0),
            )

            val vm = newVm()
            val collected = runAndCollect { vm.export(android.net.Uri.EMPTY) }
            assertEquals(1, collected.size)
            assertEquals(SettingsUiState.Done, vm.state.value)

            vm.acknowledge()
            assertEquals(SettingsUiState.Idle, vm.state.value)

            // 再 ack 一次（幂等），且不重新 emit
            vm.acknowledge()
            advanceUntilIdle()
            assertEquals(SettingsUiState.Idle, vm.state.value)
            assertEquals("ack 不应重新 emit", 1, emittedEvents.size)
        }

    @Test
    fun import_failure_marks_event_as_error_with_cause() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any())).doThrow(BackupException("版本不匹配"))

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.import(android.net.Uri.EMPTY, ImportMode.REPLACE)
                }

            assertEquals(1, collected.size)
            assertTrue("失败应标记 isError=true", collected[0].isError)
            assertTrue(
                "失败消息应含「版本不匹配」，实际：${collected[0].message}",
                collected[0].message.contains("版本不匹配"),
            )
        }

    // ════════════════════════════════════════════════════════════
    // Cancel
    // ════════════════════════════════════════════════════════════

    @Test
    fun cancel_active_export_does_not_emit_done_event() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            // 模拟 export 协程被 cancel 打断：直接让 mock 抛 CancellationException。
            whenever(backup.export(any())).doAnswer {
                throw kotlinx.coroutines.CancellationException("cancelled by user")
            }

            val vm = newVm()
            val collected = runAndCollect { vm.export(android.net.Uri.EMPTY) }

            // cancel 路径**不**发 Done 事件（SettingsViewModel.export 内 catch
            // CancellationException 直接 _state.value = Idle，不走 finalize）。
            assertTrue("cancel 不应 emit Done 事件，实际：$collected", collected.isEmpty())
            assertEquals(SettingsUiState.Idle, vm.state.value)
        }

    // ════════════════════════════════════════════════════════════
    // REPLACE 模式 + imageUriUserCount 显式提示
    // ════════════════════════════════════════════════════════════

    @Test
    fun replace_import_with_image_warning_message() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.import(any(), any())).doReturn(
                ImportResult(
                    cardsAdded = 5,
                    foldersAdded = 2,
                    transactionsAdded = 10,
                    imageUriUserCount = 3,
                ),
            )

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.import(android.net.Uri.EMPTY, ImportMode.REPLACE)
                }

            assertEquals(1, collected.size)
            val msg = collected[0].message
            assertTrue("REPLACE 消息应含「覆盖成功」，实际：$msg", msg.contains("覆盖成功"))
            assertTrue("消息应含「5 张卡」，实际：$msg", msg.contains("5 张卡"))
            assertTrue("消息应含「2 个文件夹」，实际：$msg", msg.contains("2 个文件夹"))
            assertTrue("消息应含「10 笔流水」，实际：$msg", msg.contains("10 笔流水"))
            assertTrue(
                "imageUriUserCount=3 应触发「3 张卡...重新上传」，实际：$msg",
                msg.contains("3 张卡") && msg.contains("重新上传"),
            )
        }

    // ════════════════════════════════════════════════════════════
    // Extras 全触发
    // ════════════════════════════════════════════════════════════

    @Test
    fun merge_import_with_all_extras_triggers_all_submessages() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            // 触发所有 5 个 extras：transactionsSkipped / cardsSkippedInvalidFolder /
            // duplicateFolderNames / duplicateCardNames / imageUriUserCount
            whenever(backup.import(any(), any())).doReturn(
                ImportResult(
                    cardsAdded = 8,
                    foldersAdded = 3,
                    transactionsAdded = 20,
                    transactionsSkipped = 2,
                    cardsSkippedInvalidFolder = 1,
                    duplicateFolderNames = 1,
                    duplicateCardNames = 2,
                    imageUriUserCount = 4,
                ),
            )

            val collected =
                runAndCollect {
                    val vm = newVm()
                    vm.import(android.net.Uri.EMPTY, ImportMode.MERGE)
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
                "cardsSkippedInvalidFolder=1 应触发「1 张卡...未分组」，实际：$msg",
                msg.contains("1 张卡") && msg.contains("未分组"),
            )
            assertTrue(
                "duplicateFolderNames=1 应触发「1 个文件夹...重名」，实际：$msg",
                msg.contains("1 个文件夹") && msg.contains("重名"),
            )
            assertTrue(
                "duplicateCardNames=2 应触发「2 张卡...重名」，实际：$msg",
                msg.contains("2 张卡") && msg.contains("重名"),
            )
            assertTrue(
                "imageUriUserCount=4 应触发「4 张卡...重新上传」，实际：$msg",
                msg.contains("4 张卡") && msg.contains("重新上传"),
            )
        }

    // ════════════════════════════════════════════════════════════
    // 边界：finalize 先写 state 再 emit
    // ════════════════════════════════════════════════════════════

    @Test
    fun finalize_writes_done_state_and_emits_exactly_once() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            whenever(backup.export(any())).doReturn(
                ExportSummary(cardCount = 1, folderCount = 0, transactionCount = 0),
            )

            val vm = newVm()
            // 验证 finalize 的顺序保证：emitSettings 被调用的那一刻，state 必须已经写成 Done。
            onEmitSettings = {
                assertEquals("emitSettings 时 state 应已是 Done", SettingsUiState.Done, vm.state.value)
            }
            val collected = runAndCollect { vm.export(android.net.Uri.EMPTY) }

            // finalize 顺序 = _state.value = Done → emitSettings：state 写到 Done，事件发出 1 条。
            assertEquals(1, collected.size)
            assertEquals(SettingsUiState.Done, vm.state.value)
        }
}
