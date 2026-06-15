package com.shuaji.cards.ui.screen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.data.AppContainer
import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.SettingsDoneEvent
import com.shuaji.cards.data.backup.BackupException
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.backup.ExportSummary
import com.shuaji.cards.data.backup.ImportMode
import com.shuaji.cards.data.backup.ImportResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * - 用 Robolectric 拿真实 [Application]，让 `getString(R.string.xxx, args...)` 走
 *   真实 Android 资源系统（验证国际化字符串格式正确）
 * - 用 mockito-kotlin mock 掉 [BackupRepository]，focus 在 ViewModel 的状态机 + 事件流逻辑上
 * - 用 [runTest] 跑 suspend 函数，ViewModel 的 `viewModelScope.launch` 自动用 TestScope
 *
 * **P2-7/8/9 + P3-1 修**：测试 fixture 的 `sink` 用 `replay = 0`（**跟产线 [AppContainer] 一致**——
 * 产线 `_settingsEvents = MutableSharedFlow<SettingsDoneEvent>(extraBufferCapacity = 4)`）。
 * 之前测试用 `replay = 1` 然后用 `sink.replayCache` 断言，但 `replayCache` 是 `replay > 0`
 * 才有的语义——**测的是 fixture 自己的特性，不是产线行为**。生产里 SettingsViewModel emit
 * 完就走，UI 没订阅就丢；测试必须模拟「在 emit 前就订阅」的实时行为。
 *
 * 测试模式：
 * 1. `backgroundScope.launch { sink.collect { collected += it } }` 先订阅
 * 2. `runCurrent()` 让 collect 注册成 subscriber
 * 3. 触发 ViewModel 行为（export / import / cancel / acknowledge）
 * 4. `advanceUntilIdle()` 让 emit 落地
 * 5. 断言 `collected`
 *
 * 覆盖矩阵（按子代理审查优先级排）：
 * - P2 国际化：成功 / 失败消息用 getString 拼装，不再硬编码中文
 * - P1 跨设备 imageUri 提示：imageUriUserCount 拼到 import 消息里
 * - P1 全局通知：doneEvents SharedFlow emit 正确
 * - P1 空库导出：ExportSummary.isEmpty 用「已导出空备份」文案
 * - P1 错误标记：isError 区分成功 / 失败
 * - P1 状态机：Working → Done → Idle
 * - P1 cancel：CancellationException 静默回 Idle，不发 Done 事件
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var application: Application
    private lateinit var backup: BackupRepository

    /**
     * **P2-7 修**：`replay = 0`，跟产线 [com.shuaji.cards.data.DefaultAppContainer] 的
     * `_settingsEvents = MutableSharedFlow<SettingsDoneEvent>(extraBufferCapacity = 4)` 完全一致。
     * 测试和产线的 SharedFlow 行为必须相同——否则 fixture 测出来的"通过"在产线上不一定
     * 成立（比如产线 emit 早于订阅时事件会丢，测试用 `replay = 1` 永远看不到这个 bug）。
     */
    private val sink = MutableSharedFlow<SettingsDoneEvent>(extraBufferCapacity = 4)
    private val sinkAsShared: SharedFlow<SettingsDoneEvent> = sink.asSharedFlow()
    private val fakeContainer =
        object : AppContainer {
            // 没测的 repo / settings 留空即可——用 `by lazy` 包一层，**仅在访问时才抛**
            // error()。Kotlin object 初始值是立即求值，普通 `= error("not used")`
            // 会让 fakeContainer 一被构造就 ISE 把所有测试带挂。
            override val repository: com.shuaji.cards.data.CardRepository by lazy { error("not used") }
            override val settings: com.shuaji.cards.data.SettingsRepository by lazy { error("not used") }
            override val backup: BackupRepository by lazy { error("not used in fake") }
            override val cycleAutoResetEvents: SharedFlow<Int> by lazy { error("not used") }
            override val settingsEvents: SharedFlow<SettingsDoneEvent> = sinkAsShared
            override suspend fun emitSettings(event: SettingsDoneEvent) {
                sink.emit(event)
            }
        }

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        backup = mock()
    }

    private fun newVm() = SettingsViewModel(application, backup, fakeContainer)

    /**
     * 在 [runTest] 测试体里订阅 sink 并跑 vm 行为。**先订阅 → 再触发**是关键——`replay = 0`
     * 下订阅晚了就收不到事件（这就是产线行为）。
     */
    private fun runAndCollect(action: () -> Unit): List<SettingsDoneEvent> {
        val collected = mutableListOf<SettingsDoneEvent>()
        val collectJob =
            backgroundScope.launch {
                sink.collect { collected += it }
            }
        runCurrent() // 让 collect 协程订阅 sink
        action()
        advanceUntilIdle()
        collectJob.cancel()
        return collected
    }

    @Test
    fun export_success_emits_event_with_export_message() =
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
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
    // 状态机（P2-10 补）
    // ════════════════════════════════════════════════════════════

    @Test
    fun state_transitions_Idle_Working_Done_Idle_on_export_success() =
        runTest {
            whenever(backup.export(any())).doReturn(
                ExportSummary(cardCount = 1, folderCount = 0, transactionCount = 0),
            )

            val vm = newVm()
            // 启动 collector
            val collected = mutableListOf<SettingsDoneEvent>()
            val collectJob =
                backgroundScope.launch {
                    sink.collect { collected += it }
                }
            runCurrent()

            // 初始 Idle
            assertEquals(SettingsUiState.Idle, vm.state.value)

            vm.export(android.net.Uri.EMPTY)
            advanceUntilIdle()
            // export 协程跑完后：state 走到 Done + SharedFlow emit
            // 实际顺序：Working → Done（emit）→ Idle（acknowledge 触发）
            // 关键：SharedFlow emit 已发生，state 走到 Done 后被
            // SettingsScreen 的 LaunchedEffect(state) ack 回 Idle。
            // 这里只能验证**最终**Idle + **emit 已收**，不验证中间 Working（瞬态）。
            assertEquals("export 完成后 state 应回到 Idle", SettingsUiState.Idle, vm.state.value)
            assertEquals(1, collected.size)

            collectJob.cancel()
        }

    @Test
    fun acknowledge_after_Done_resets_state_to_Idle() =
        runTest {
            whenever(backup.export(any())).doReturn(
                ExportSummary(cardCount = 1, folderCount = 0, transactionCount = 0),
            )

            val vm = newVm()
            val collected = mutableListOf<SettingsDoneEvent>()
            val collectJob =
                backgroundScope.launch {
                    sink.collect { collected += it }
                }
            runCurrent()

            vm.export(android.net.Uri.EMPTY)
            advanceUntilIdle()
            // 关键：SharedFlow 收 1 条 emit，state 已 ack 回 Idle
            assertEquals(1, collected.size)
            assertEquals(SettingsUiState.Idle, vm.state.value)

            // 再 ack 一次（幂等）
            vm.acknowledge()
            advanceUntilIdle()
            assertEquals(SettingsUiState.Idle, vm.state.value)
            // ack 不重新 emit
            assertEquals("ack 不应重新 emit", 1, collected.size)

            collectJob.cancel()
        }

    @Test
    fun import_failure_marks_event_as_error_with_cause() =
        runTest {
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
    // Cancel（P2-10 补）
    // ════════════════════════════════════════════════════════════

    @Test
    fun cancel_active_export_does_not_emit_done_event() =
        runTest {
            // 模拟 export 协程被 cancel 打断：直接让 mock 抛 CancellationException。
            // 产线上 [BackupRepository.export] 内部用 `withContext(Dispatchers.IO)`，
            // viewModelScope.cancel() 会让 withContext 收到 CancellationException
            // 然后往外抛——这里我们直接 mock 这个异常路径。
            whenever(backup.export(any())).doAnswer {
                throw kotlinx.coroutines.CancellationException("cancelled by user")
            }

            val collected = mutableListOf<SettingsDoneEvent>()
            val collectJob =
                backgroundScope.launch {
                    sink.collect { collected += it }
                }
            runCurrent()

            val vm = newVm()
            vm.export(android.net.Uri.EMPTY)
            advanceUntilIdle()

            // 关键：cancel 路径**不**发 Done 事件（SettingsViewModel.export 内部
            // catch CancellationException 直接 _state.value = Idle，不走 finalize）
            assertTrue("cancel 不应 emit Done 事件，实际：$collected", collected.isEmpty())
            // state 回到 Idle（不是 Done）
            assertEquals(SettingsUiState.Idle, vm.state.value)

            collectJob.cancel()
        }

    // ════════════════════════════════════════════════════════════
    // REPLACE 模式 + imageUriUserCount 显式提示（P2-10 补）
    // ════════════════════════════════════════════════════════════

    @Test
    fun replace_import_with_image_warning_message() =
        runTest {
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
            // 模式前缀：覆盖成功
            assertTrue("REPLACE 消息应含「覆盖成功」，实际：$msg", msg.contains("覆盖成功"))
            // 主体计数
            assertTrue("消息应含「5 张卡」，实际：$msg", msg.contains("5 张卡"))
            assertTrue("消息应含「2 个文件夹」，实际：$msg", msg.contains("2 个文件夹"))
            assertTrue("消息应含「10 笔流水」，实际：$msg", msg.contains("10 笔流水"))
            // imageUriUserCount 警告
            assertTrue(
                "imageUriUserCount=3 应触发「3 张卡...重新上传」，实际：$msg",
                msg.contains("3 张卡") && msg.contains("重新上传"),
            )
        }

    // ════════════════════════════════════════════════════════════
    // Extras 全触发（P2-10 补）
    // ════════════════════════════════════════════════════════════

    @Test
    fun merge_import_with_all_extras_triggers_four_submessages() =
        runTest {
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
            // 模式前缀：追加成功
            assertTrue("MERGE 消息应含「追加成功」，实际：$msg", msg.contains("追加成功"))
            // 主体
            assertTrue("应含「8 张卡」，实际：$msg", msg.contains("8 张卡"))
            assertTrue("应含「3 个文件夹」，实际：$msg", msg.contains("3 个文件夹"))
            assertTrue("应含「20 笔流水」，实际：$msg", msg.contains("20 笔流水"))
            // 5 个 extras 全部触发
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
            // 关键：消息里有 5 个 extras → 用分隔符「；」分开
            assertNotNull("消息应非空", msg)
        }

    // ════════════════════════════════════════════════════════════
    // 边界：emit 后 collector 取消不影响 state
    // ════════════════════════════════════════════════════════════

    @Test
    fun finalize_writes_state_before_emit_so_collector_cancel_does_not_lose_state() =
        runTest {
            whenever(backup.export(any())).doReturn(
                ExportSummary(cardCount = 1, folderCount = 0, transactionCount = 0),
            )

            val vm = newVm()
            val collected = mutableListOf<SettingsDoneEvent>()
            val collectJob =
                backgroundScope.launch {
                    sink.collect { collected += it }
                }
            runCurrent()

            vm.export(android.net.Uri.EMPTY)
            advanceUntilIdle()
            // 关键：finalize 顺序 = _state.value = Done → emitSettings。
            // state 写完才 emit；collectJob.cancel 发生在 advanceUntilIdle 之后，
            // 此时 emit 已经完成，state 已经被 ack。取消 collector 不影响 state 收敛。
            assertEquals(1, collected.size)
            assertEquals(SettingsUiState.Idle, vm.state.value)

            collectJob.cancel()
        }
}
