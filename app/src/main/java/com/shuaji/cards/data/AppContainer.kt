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
     * 设置页结果事件流：ViewModel 发布 [SettingsDoneEvent]，
     * `ShuajiApp` 顶层 SnackbarHost 负责在当前应用页面展示。
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
     * 对外仅暴露只读 [SharedFlow]，发布经过该接口收口。
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
     * 启动期竞争：[ShuajiApplication.onCreate] 调 [CardRepository.resetOverdueCycles] →
     * emit 到 `_cycleAutoResetEvents`；同时 `ShuajiApp` 的 `LaunchedEffect(cycleEvents)`
     * 在 Compose 第一次组合后订阅。Application.onCreate → DB init → 查询 → emit 是一
     * 串异步操作，emit 可能早于 collector 订阅。
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

    /** 把设置页结果事件发布到顶层 SnackbarHost。 */
    override suspend fun emitSettings(event: SettingsDoneEvent) {
        _settingsEvents.emit(event)
    }
}

/**
 * 设置页跨页面通知载荷。
 *
 * 用 `data class` 而不是 `sealed class` 因为所有事件最终都映射成「Snackbar
 * 文本」一个出口，UI 层不需要按事件类型分流。
 * `isError` 供 UI 区分错误与成功提示。
 */
data class SettingsDoneEvent(
    val message: String,
    val isError: Boolean,
)
