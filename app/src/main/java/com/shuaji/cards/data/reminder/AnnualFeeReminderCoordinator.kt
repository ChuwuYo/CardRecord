package com.shuaji.cards.data.reminder

import android.util.Log
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.processForegroundFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId

class AnnualFeeReminderCoordinator(
    private val repository: CardRepository,
    private val store: AnnualFeeReminderStore,
    private val scheduler: AnnualFeeReminderScheduler,
    private val notifier: AnnualFeeReminderNotifier,
    private val enabledFlow: Flow<Boolean>,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val foreground: Flow<Boolean> = processForegroundFlow(),
) {
    private val refreshTick = MutableStateFlow(0L)

    private val planLock = Any()

    fun requestRefresh() {
        refreshTick.value = refreshTick.value + 1L
    }

    fun cancelAllTrackedAlarms() {
        synchronized(planLock) {
            scheduler.cancelAllTracked()
        }
    }

    fun start(scope: CoroutineScope) {
        notifier.ensureChannel()
        scope.launch {
            foreground
                .distinctUntilChanged()
                .collectLatest { isForeground ->
                    if (isForeground) requestRefresh()
                }
        }
        scope.launch {
            combine(
                repository.observeCards(),
                enabledFlow,
                refreshTick,
            ) { cards, enabled, _ ->
                cards to enabled
            }.collectLatest { (cards, enabled) ->
                applyPlan(cards, enabled)
            }
        }
    }

    internal fun applyPlan(
        cards: List<com.shuaji.cards.data.CardWithCount>,
        enabled: Boolean,
    ) {
        synchronized(planLock) {
            try {
                val canPost = notifier.canPostNotifications()
                if (!enabled || !canPost) {
                    scheduler.cancelAllTracked()
                    return
                }
                val liveCardDueTokens =
                    cards
                        .mapNotNull { item ->
                            val due = item.card.nextDueDateMillis ?: return@mapNotNull null
                            item.card.id to due
                        }.toSet()
                store.pruneNotifiedKeeping(liveCardDueTokens)
                val plan =
                    AnnualFeeReminderPlanner.plan(
                        cards = cards,
                        enabled = true,
                        now = clock.instant(),
                        zoneId = zoneIdProvider(),
                        alreadyNotified = store::wasNotified,
                    )
                scheduler.replaceAll(plan)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                Log.w("AnnualFeeReminder", "applyPlan failed", error)
            } catch (error: SecurityException) {
                Log.w("AnnualFeeReminder", "applyPlan failed", error)
            }
        }
    }

    suspend fun rescheduleFromStore() {
        val cards = repository.observeCards().first()
        applyPlan(cards, store.isEnabled())
    }
}
