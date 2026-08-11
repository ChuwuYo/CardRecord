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

/**
 * 订阅卡片派生快照与提醒开关，随时用最新计划替换 AlarmManager 条目。
 *
 * 主路径是后台非精确闹钟；打开 App / 改数据只是触发重排，不是「打开才提醒」。
 *
 * 闹钟「自动创建」时机（提醒开关已开 + 系统允许通知时）：
 * 1. 系统权限弹窗回调 / 从系统通知设置返回（设置页 refresh → requestRefresh）
 * 2. 进程回到前台（[processForegroundFlow] ON_START）
 * 3. 提醒开关或卡片数据变化
 * 4. 开机 / 时区或系统时间变化（BootReceiver）
 * 5. 某次闹钟触发后的收尾重排
 *
 * 用户在系统设置里开了权限但一直不回到本 App：要等到下次把进程带回前台或冷启动才会挂上。
 * 无法在未回到进程时侦测系统授权（Android 无此广播）。
 */
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
    /** 递增即可踢一次重排（权限变化 / 时区 / 闹钟回调后）。 */
    private val refreshTick = MutableStateFlow(0L)

    /**
     * 串行化所有 [applyPlan] / 取消：Boot、闹钟回调与 Flow 重排可能并行触达，
     * 否则两趟 [AnnualFeeReminderScheduler.replaceAll] 交错会留下未登记或过期闹钟。
     */
    private val planLock = Any()

    fun requestRefresh() {
        refreshTick.value = refreshTick.value + 1L
    }

    /** REPLACE 导入等场景：在清空 prefs 登记前同步取消 AlarmManager 条目。 */
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

    /**
     * 无卡片流订阅时（例如仅 Receiver 回调）也可同步按当前快照重排。
     * 测试与 [requestRefresh] 最终都落到同一套 plan 规则。
     */
    internal fun applyPlan(
        cards: List<com.shuaji.cards.data.CardWithCount>,
        enabled: Boolean,
    ) {
        synchronized(planLock) {
            try {
                val canPost = notifier.canPostNotifications()
                if (!enabled || !canPost) {
                    // 无权限时不排闹钟，避免触发后无法投递又反复补排。
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
            } catch (error: RuntimeException) {
                // 测试拆卸或 OEM 异常不得打崩后台协程；下次 refresh / 前台再试。
                Log.w("AnnualFeeReminder", "applyPlan failed", error)
            }
        }
    }

    /** 供 Receiver / Boot 在没有最新 Flow 排放时拉取快照重排。 */
    suspend fun rescheduleFromStore() {
        val cards = repository.observeCards().first()
        applyPlan(cards, store.isEnabled())
    }
}
