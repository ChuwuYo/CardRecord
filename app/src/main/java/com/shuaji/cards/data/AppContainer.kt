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

    val reminderScope: CoroutineScope

    fun requestReminderReschedule()

    suspend fun rescheduleRemindersFromStore()

    val annualFeeCycleEvents: Flow<AnnualFeeCycleEvent>

    val settingsEvents: Flow<SettingsDoneEvent>

    suspend fun emitSettings(event: SettingsDoneEvent)

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

    override suspend fun emitSettings(event: SettingsDoneEvent) {
        settingsEventQueue.emit(event)
    }
}

data class SettingsDoneEvent(
    val message: String,
    val isError: Boolean,
)
