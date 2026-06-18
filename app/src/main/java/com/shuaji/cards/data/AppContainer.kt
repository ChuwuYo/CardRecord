package com.shuaji.cards.data

import android.content.Context
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.local.AppDatabase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface AppContainer {
    val repository: CardRepository
    val settings: SettingsRepository
    val backup: BackupRepository

    /**
     * 自动续期事件：值 = 本次启动时续期的卡数。
     * Application.onCreate 跑 [CardRepository.resetOverdueCycles]，结果 emit 到这里；
     * UI 层订阅后弹 Snackbar 告知用户。值 = 0 不发事件（避免噪音）。
     */
    val cycleAutoResetEvents: SharedFlow<Int>

    /**
     * 设置页 Done 事件流：值 = ViewModel 已经把消息拼好的 [SettingsDoneEvent]（id +
     *   message + 优先级），推到顶层 `ShuajiApp` 全局 SnackbarHost。
     *
     * **P1 修**：原来 SettingsScreen 自己持有一个 `SnackbarHostState`，结果就是
     * 用户在 SettingsScreen 点完「导出」→「已导出 N 条」提示弹出来，但只要他
     * 跳到 Home 或者锁屏 / 通知就看不到——消息随页面销毁丢了。
     * 现在改成 emit 到这里的 SharedFlow，顶层订阅，在任意页面 / 锁屏 / 通知
     * 都能消费（Material3 Snackbar 内部就是基于 `SnackbarHostState` 队列，
     * 不会因为 setContent 重组而丢）。
     *
     * **为什么 ViewModel 不直接 emit 字符串？**
     * 1) ViewModel 不该持有 Context，调 `getString(R.string.xxx, ...)` 需要
     *    Application。Application 注入放到 ViewModel 构造里。
     * 2) 字符串拼装逻辑集中在 ViewModel，UI 层只负责把消息文本丢给 Snackbar。
     *    这样多语言 / 文案微调都在 strings.xml 一处改。
     */
    val settingsEvents: SharedFlow<SettingsDoneEvent>

    /**
     * 发送一条设置页事件（AppContainer 同时是发布者和容器）。
     *
     * 暴露 [emitSettings] 给 ViewModel 写，比让 ViewModel 反射拿 `MutableSharedFlow`
     * 干净——对外只发不可改的 read-only `SharedFlow`，写入端只在 [DefaultAppContainer] 内部。
     */
    suspend fun emitSettings(event: SettingsDoneEvent)

    /**
     * 启动时跑一次到期续期：把所有 nextDueDateMillis 已过的卡续期（删流水 + 推 1 年），
     * 并将续期卡数 emit 到 [cycleAutoResetEvents]。逻辑收口到容器内，
     * [ShuajiApplication] 只依赖接口、不再向下转型到 [DefaultAppContainer]。
     */
    suspend fun runStartupCycleReset(nowMillis: Long)
}

class DefaultAppContainer(
    context: Context,
) : AppContainer {
    private val database = AppDatabase.get(context)
    override val repository: CardRepository =
        CardRepository(
            cardDao = database.cardDao(),
            transactionDao = database.transactionDao(),
            folderDao = database.cardFolderDao(),
        )
    override val settings: SettingsRepository = SettingsRepository(context.appDataStore)
    override val backup: BackupRepository =
        BackupRepository(
            context = context,
            database = database,
            cardDao = database.cardDao(),
            folderDao = database.cardFolderDao(),
            transactionDao = database.transactionDao(),
        )

    /**
     * P1-1 修：用 `replay = 1` + `BufferOverflow.DROP_OLDEST`。
     *
     * 启动期竞争：[ShuajiApplication.onCreate] 调 [CardRepository.resetOverdueCycles] →
     * emit 到 `_cycleAutoResetEvents`；同时 `ShuajiApp` 的 `LaunchedEffect(cycleEvents)`
     * 在 Compose 第一次组合后订阅。Application.onCreate → DB init → 查询 → emit 是一
     * 串异步操作，**如果 emit 跑在 collector 订阅之前，无 `replay` 的话事件直接被丢**。
     * 用户永远看不到「X 张卡已自动续期」提示。
     *
     * `replay = 1` 让新 collector 立即收到最近一次 emit；`DROP_OLDEST` 避免极小概率
     * 的"两次重置"情况下 buffer 撑爆挂起。
     */
    private val _cycleAutoResetEvents =
        MutableSharedFlow<Int>(
            replay = 1,
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val cycleAutoResetEvents: SharedFlow<Int> = _cycleAutoResetEvents.asSharedFlow()

    /**
     * 设置页 Done 事件流：`SettingsViewModel.emitSettings` 推，`ShuajiApp` 顶层
     * SnackbarHost 订阅。**用 `replay = 0`**——用户从设置页跳走再跳回来，事件已弹过；
     * 不再 replay 旧消息。
     */
    private val _settingsEvents = MutableSharedFlow<SettingsDoneEvent>(extraBufferCapacity = 4)
    override val settingsEvents: SharedFlow<SettingsDoneEvent> = _settingsEvents.asSharedFlow()

    /** 把仓库结果 emit 到 SharedFlow（count == 0 不发，避免噪音）。 */
    private suspend fun emitCycleAutoReset(count: Int) {
        if (count > 0) _cycleAutoResetEvents.emit(count)
    }

    override suspend fun runStartupCycleReset(nowMillis: Long) {
        val resetCount = repository.resetOverdueCycles(nowMillis)
        emitCycleAutoReset(resetCount)
    }

    /** P1 修：把设置页 Done 事件 emit 到 SharedFlow，顶层 SnackbarHost 消费。 */
    override suspend fun emitSettings(event: SettingsDoneEvent) {
        _settingsEvents.emit(event)
    }
}

/**
 * 设置页跨页面通知载荷。
 *
 * 用 `data class` 而不是 `sealed class` 因为所有事件最终都映射成「Snackbar
 * 文本」一个出口，UI 层不需要分类型做不同处理（dismiss vs action 等都在
 * 文本层面 + 默认 Snackbar 行为覆盖）。`isError = true` 让 UI 决定是否
 * 用错误主题色（红色）显示。
 *
 * 历史：早期 v1.5.0 draft 我用 `sealed class SettingsUiEvent { ... }`，
 * 但 SettingsScreen 内的 `state is SettingsUiState.Done` 已经把类型消
 * 化掉了；emit 到全局时再分类型就是双重抽象 + 没有 UI 差异点——直接平铺。
 */
data class SettingsDoneEvent(
    val message: String,
    val isError: Boolean,
)
