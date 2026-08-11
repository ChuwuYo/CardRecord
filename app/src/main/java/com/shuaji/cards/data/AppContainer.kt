package com.shuaji.cards.data

import android.content.Context
import com.shuaji.cards.core.OneShotEventQueue
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.reminder.AnnualFeeReminderCoordinator
import com.shuaji.cards.data.reminder.AnnualFeeReminderNotifier
import com.shuaji.cards.data.reminder.AnnualFeeReminderScheduler
import com.shuaji.cards.data.reminder.AnnualFeeReminderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Clock
import java.time.ZoneId

interface AppContainer {
    val repository: CardRepository
    val settings: SettingsRepository
    val backup: BackupRepository
    val reminderStore: AnnualFeeReminderStore
    val reminderNotifier: AnnualFeeReminderNotifier

    /** Receiver 异步发通知用；生命周期绑进程。 */
    val reminderScope: CoroutineScope

    /**
     * 权限/时区/闹钟回调后强制按当前快照重排本地提醒。
     * 不依赖 `enabled` 或卡片流是否变化。
     */
    fun requestReminderReschedule()

    /**
     * 同步拉取卡片快照并重排（供 Boot / 闹钟 Receiver 在 `goAsync` 协程内调用）。
     * 与 [requestReminderReschedule] 最终同一套 plan 规则，但不依赖 Flow 排放时机。
     */
    suspend fun rescheduleRemindersFromStore()

    /**
     * 自动续期事件：前台首发或跨零时归一化成功/失败后 emit 到这里，
     * UI 层订阅后显示对应 Snackbar。事件经单次消费队列交付，不会在 UI 重建后重放；
     * 归一化数量为 0 时不发成功事件，避免噪音。
     */
    val annualFeeCycleEvents: Flow<AnnualFeeCycleEvent>

    /**
     * 设置页结果事件流：ViewModel 发布 [SettingsDoneEvent]，
     * `ShuajiApp` 顶层 SnackbarHost 负责在当前应用页面展示。
     */
    val settingsEvents: Flow<SettingsDoneEvent>

    /**
     * 发送一条设置页事件（AppContainer 同时是发布者和容器）。
     *
     * 事件在 Activity 重建的短暂无订阅窗口仍会排队，但每条只消费一次。
     */
    suspend fun emitSettings(event: SettingsDoneEvent)

    /** 启动图片迁移/回收、年费周期协调器与本地提醒调度；[ShuajiApplication] 只负责调用一次。 */
    fun startBackgroundWork(scope: CoroutineScope)
}

class DefaultAppContainer(
    context: Context,
    startupThemeModeCache: ThemeModeStartupCache = SharedPreferencesThemeModeStartupCache(context),
) : AppContainer {
    private val appContext = context.applicationContext
    private val database = AppDatabase.get(context)
    private val clock = Clock.systemUTC()
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
    private val boundaryTicks = localMidnightTicks(clock, zoneIdProvider)
    private val userImages =
        ContentResolverUserCardImageStore(
            contentResolver = context.contentResolver,
            cardDao = database.cardDao(),
            rootDirectory = File(context.filesDir, "user_card_images"),
        )
    override val repository: CardRepository =
        CardRepository(
            database = database,
            cardDao = database.cardDao(),
            transactionDao = database.transactionDao(),
            folderDao = database.cardFolderDao(),
            clock = clock,
            zoneIdProvider = zoneIdProvider,
            boundaryTicks = boundaryTicks,
            userImages = userImages,
        )
    override val settings: SettingsRepository = SettingsRepository(context.appDataStore, startupThemeModeCache)
    override val reminderStore = AnnualFeeReminderStore(appContext)
    override val reminderNotifier = AnnualFeeReminderNotifier(appContext)
    private val reminderScheduler = AnnualFeeReminderScheduler(appContext, reminderStore)
    override val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val annualFeeReminderCoordinator =
        AnnualFeeReminderCoordinator(
            repository = repository,
            store = reminderStore,
            scheduler = reminderScheduler,
            notifier = reminderNotifier,
            enabledFlow = reminderStore.observeEnabled(),
            clock = clock,
            zoneIdProvider = zoneIdProvider,
        )

    override val backup: BackupRepository =
        BackupRepository(
            context = context,
            database = database,
            cardDao = database.cardDao(),
            folderDao = database.cardFolderDao(),
            transactionDao = database.transactionDao(),
            normalizeInTransaction = repository::normalizeOverdueCyclesInTransaction,
            userImages = userImages,
            reminderStore = reminderStore,
            onRemindersPreferenceApplied = annualFeeReminderCoordinator::requestRefresh,
            cancelTrackedReminders = annualFeeReminderCoordinator::cancelAllTrackedAlarms,
        )

    private val annualFeeCycleEventQueue = AnnualFeeCycleEventQueue()
    override val annualFeeCycleEvents: Flow<AnnualFeeCycleEvent> = annualFeeCycleEventQueue.events

    private val settingsEventQueue = OneShotEventQueue<SettingsDoneEvent>()
    override val settingsEvents: Flow<SettingsDoneEvent> = settingsEventQueue.events

    /** 协调器与 Repository 共享同一个首发/跨零时 ticker，避免重复归一化。 */
    private val annualFeeCycleCoordinator =
        AnnualFeeCycleCoordinator(
            normalize = repository::normalizeOverdueCycles,
            boundaryTicks = boundaryTicks,
            foreground = processForegroundFlow(),
            onEvent = annualFeeCycleEventQueue::emit,
        )

    override fun startBackgroundWork(scope: CoroutineScope) {
        scope.launch { repository.maintainUserImagesOnStartupBestEffort() }
        annualFeeCycleCoordinator.start(scope)
        annualFeeReminderCoordinator.start(scope)
    }

    override fun requestReminderReschedule() {
        annualFeeReminderCoordinator.requestRefresh()
    }

    override suspend fun rescheduleRemindersFromStore() {
        annualFeeReminderCoordinator.rescheduleFromStore()
    }

    /** 把设置页结果事件发布到顶层 SnackbarHost。 */
    override suspend fun emitSettings(event: SettingsDoneEvent) {
        settingsEventQueue.emit(event)
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
