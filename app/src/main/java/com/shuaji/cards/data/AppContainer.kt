package com.shuaji.cards.data

import android.content.Context
import com.shuaji.cards.core.OneShotEventQueue
import com.shuaji.cards.data.backup.BackupRepository
import com.shuaji.cards.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
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
     */
    val settingsEvents: Flow<SettingsDoneEvent>

    /**
     * 发送一条设置页事件（AppContainer 同时是发布者和容器）。
     *
     * 事件在 Activity 重建的短暂无订阅窗口仍会排队，但每条只消费一次。
     */
    suspend fun emitSettings(event: SettingsDoneEvent)

    /** 启动图片迁移/回收与前台年度周期协调器；[ShuajiApplication] 只负责调用一次。 */
    fun startBackgroundWork(scope: CoroutineScope)
}

class DefaultAppContainer(
    context: Context,
    startupThemeModeCache: ThemeModeStartupCache = SharedPreferencesThemeModeStartupCache(context),
) : AppContainer {
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
    override val backup: BackupRepository =
        BackupRepository(
            context = context,
            database = database,
            cardDao = database.cardDao(),
            folderDao = database.cardFolderDao(),
            transactionDao = database.transactionDao(),
            normalizeInTransaction = repository::normalizeOverdueCyclesInTransaction,
            userImages = userImages,
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
