package com.shuaji.cards.data

import android.content.Context
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Clock
import java.time.ZoneId

interface AppContainer {
    val repository: CardRepository
    val settings: SettingsRepository
    val backup: BackupRepository

    /**
     * 自动续期事件：前台首发或跨零时归一化成功/失败后 emit 到这里，
     * UI 层订阅后显示对应 Snackbar。事件经单次消费队列交付，不会在 UI 重建后重放；
     * 归一化数量为 0 时不发成功事件，避免噪音。
     */
    val annualFeeCycleEvents: Flow<AnnualFeeCycleEvent>

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
     * 启动应用级协调器：仅在进程前台消费边界时钟，失败时按规则重试。
     * [ShuajiApplication] 只负责启动一次，不再额外执行一次性归一化。
     */
    fun startAnnualFeeCycleCoordinator(scope: CoroutineScope): Job
}

class DefaultAppContainer(
    context: Context,
) : AppContainer {
    private val database = AppDatabase.get(context)
    private val clock = Clock.systemUTC()
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
    private val boundaryTicks = localMidnightTicks(clock, zoneIdProvider)
    override val repository: CardRepository =
        CardRepository(
            database = database,
            cardDao = database.cardDao(),
            transactionDao = database.transactionDao(),
            folderDao = database.cardFolderDao(),
            clock = clock,
            zoneIdProvider = zoneIdProvider,
            boundaryTicks = boundaryTicks,
        )
    override val settings: SettingsRepository = SettingsRepository(context.appDataStore)
    override val backup: BackupRepository =
        BackupRepository(
            context = context,
            database = database,
            cardDao = database.cardDao(),
            folderDao = database.cardFolderDao(),
            transactionDao = database.transactionDao(),
            normalizeInTransaction = repository::normalizeOverdueCyclesInTransaction,
        )

    private val annualFeeCycleEventQueue = AnnualFeeCycleEventQueue()
    override val annualFeeCycleEvents: Flow<AnnualFeeCycleEvent> = annualFeeCycleEventQueue.events

    /**
     * 设置页 Done 事件流：`SettingsViewModel.emitSettings` 推，`ShuajiApp` 顶层
     * SnackbarHost 订阅。**用 `replay = 0`**——用户从设置页跳走再跳回来，事件已弹过；
     * 不再 replay 旧消息。
     */
    private val _settingsEvents = MutableSharedFlow<SettingsDoneEvent>(extraBufferCapacity = 4)
    override val settingsEvents: SharedFlow<SettingsDoneEvent> = _settingsEvents.asSharedFlow()

    /** 协调器与 Repository 共享同一个首发/跨零时 ticker，避免重复归一化。 */
    private val annualFeeCycleCoordinator =
        AnnualFeeCycleCoordinator(
            normalize = repository::normalizeOverdueCycles,
            boundaryTicks = boundaryTicks,
            foreground = processForegroundFlow(),
            onEvent = annualFeeCycleEventQueue::emit,
        )

    override fun startAnnualFeeCycleCoordinator(scope: CoroutineScope): Job = annualFeeCycleCoordinator.start(scope)

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
