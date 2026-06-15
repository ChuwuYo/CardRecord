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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
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
 * - 用 [TestScope] 跑 suspend 函数，ViewModel 的 `viewModelScope.launch` 自动用 TestScope
 *
 * 覆盖矩阵（按子代理审查优先级排）：
 * - P2 国际化：成功 / 失败消息用 getString 拼装，不再硬编码中文
 * - P1 跨设备 imageUri 提示：imageUriUserCount 拼到 import 消息里
 * - P1 全局通知：doneEvents SharedFlow emit 正确
 * - P1 空库导出：ExportSummary.isEmpty 用「已导出空备份」文案
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var application: Application
    private lateinit var backup: BackupRepository
    private val sink = MutableSharedFlow<SettingsDoneEvent>(
        replay = 1,
        extraBufferCapacity = 4,
    )
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

    private suspend fun TestScope.runVmAndCollect(vm: SettingsViewModel): MutableList<SettingsDoneEvent> {
        val collected = mutableListOf<SettingsDoneEvent>()
        val job =
            backgroundScope.launch {
                fakeContainer.settingsEvents.collect { collected += it }
            }
        // 给 viewModelScope.launch 一点时间触发并 emit
        advanceUntilIdle()
        job.cancel()
        return collected
    }

    @Test
    fun export_success_emits_event_with_export_message() =
        runTest {
            whenever(backup.export(any())).doReturn(
                ExportSummary(cardCount = 5, folderCount = 2, transactionCount = 12),
            )

            val vm = newVm()

            // 启动 collector（在 export 前就订阅）
            val collected = mutableListOf<SettingsDoneEvent>()
            val collectJob =
                backgroundScope.launch {
                    sink.collect { collected += it }
                }
            runCurrent() // 让 collect 协程订阅 sink

            // 等 state 走到 Done（说明 export 协程跑完）
            vm.export(android.net.Uri.EMPTY)
            advanceUntilIdle()
            collectJob.cancel()

            // 关键：replay = 1，新 collector 启动时也能拿到最近一次 emit
            // ——这里用 tryEmit/replayCache 验证：emitter 真的把 event 推进了 sink
            val cached = sink.replayCache
            assertEquals("replayCache 应含 1 条 emit，实际：${cached.size}", 1, cached.size)
            val message = cached[0].message
            assertTrue("消息应含「已导出」", message.contains("已导出"))
            assertTrue("消息应含「5 张卡」", message.contains("5 张卡"))
            assertTrue("消息应含「2 个文件夹」", message.contains("2 个文件夹"))
            assertTrue("消息应含「12 笔流水」", message.contains("12 笔流水"))
        }

    @Test
    fun export_empty_db_uses_empty_message() =
        runTest {
            whenever(backup.export(any())).doReturn(ExportSummary(0, 0, 0))

            val vm = newVm()
            vm.export(android.net.Uri.EMPTY)
            advanceUntilIdle()

            val cached = sink.replayCache
            assertEquals(1, cached.size)
            assertTrue(
                "空库应使用「已导出空备份」文案，实际：${cached[0].message}",
                cached[0].message.contains("已导出空备份"),
            )
        }

    @Test
    fun export_failure_marks_event_as_error() =
        runTest {
            whenever(backup.export(any())).doThrow(BackupException("磁盘满"))

            val vm = newVm()
            vm.export(android.net.Uri.EMPTY)
            advanceUntilIdle()

            val cached = sink.replayCache
            assertEquals(1, cached.size)
            assertTrue("失败应标记 isError=true", cached[0].isError)
            assertTrue("失败应含原因", cached[0].message.contains("磁盘满"))
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

            val vm = newVm()
            vm.import(android.net.Uri.EMPTY, ImportMode.MERGE)
            advanceUntilIdle()

            val cached = sink.replayCache
            assertEquals(1, cached.size)
            val msg = cached[0].message
            assertTrue("消息应含「3 张卡」", msg.contains("3 张卡"))
            assertTrue(
                "imageUriUserCount=2 应触发「2 张卡...重新上传」",
                msg.contains("2 张卡") && msg.contains("重新上传"),
            )
        }
}
