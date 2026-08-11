package com.shuaji.cards.data.reminder

import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardWithCount
import com.shuaji.cards.data.DateToken
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 年费进度本地提醒的纯决策层：算出每张卡、每个档位应挂的触发时刻。
 *
 * 不碰 AlarmManager / Notification——便于单测与平台边界隔离。
 */
object AnnualFeeReminderPlanner {
    /** 距结算日还剩这些天时提醒（先远后近）。 */
    val THRESHOLDS_DAYS: List<Int> = listOf(30, 10)

    data class PlannedAlarm(
        val cardId: Long,
        val dueDateToken: Long,
        val thresholdDays: Int,
        /** RTC 毫秒；若已过阈值则为 [now]，由调度层尽快触发。 */
        val triggerAtMillis: Long,
    )

    data class FireDecision(
        val shouldNotify: Boolean,
        val cardId: Long,
        val dueDateToken: Long,
        val thresholdDays: Int,
        val bank: String,
        val name: String,
        val currentCount: Int,
        val requiredCount: Int,
        val remainingDays: Long,
    )

    /**
     * @param alreadyNotified 本结算周期该档位是否已成功投递过；已通知的档位不再排闹钟，
     * 避免前台恢复重排时把「钳到 now」的闹钟反复挂上。
     */
    fun plan(
        cards: List<CardWithCount>,
        enabled: Boolean,
        now: Instant,
        zoneId: ZoneId,
        alreadyNotified: (cardId: Long, thresholdDays: Int, dueDateToken: Long) -> Boolean =
            { _, _, _ -> false },
    ): List<PlannedAlarm> {
        if (!enabled) return emptyList()
        val nowMillis = now.toEpochMilli()
        return buildList {
            for (item in cards) {
                val cycle = item.cycle
                if (cycle !is AnnualFeeCycle.Active) continue
                if (item.currentCount >= item.card.requiredCount) continue
                val dueToken = item.card.nextDueDateMillis ?: continue
                val dueDate = DateToken.normalizeAnnualDate(DateToken.toLocalDate(dueToken))
                // 每张卡只挂「下一档」闹钟，降低 while-idle 配额压力；触发后再重排下一档。
                val next =
                    THRESHOLDS_DAYS
                        .asSequence()
                        .filter { threshold ->
                            !alreadyNotified(item.card.id, threshold, dueToken) &&
                                // 补发窗口：更近档位已通知后，不再排更远且同样已过期的档，
                                // 否则 10 天补发后会立刻再排 30 天并二次通知。
                                THRESHOLDS_DAYS.none { nearer ->
                                    nearer < threshold &&
                                        alreadyNotified(item.card.id, nearer, dueToken)
                                }
                        }.map { threshold ->
                            val triggerDate = dueDate.minusDays(threshold.toLong())
                            val triggerAt =
                                triggerDate
                                    .atStartOfDay(zoneId)
                                    .toInstant()
                                    .toEpochMilli()
                            PlannedAlarm(
                                cardId = item.card.id,
                                dueDateToken = dueToken,
                                thresholdDays = threshold,
                                triggerAtMillis = maxOf(triggerAt, nowMillis),
                            )
                        }
                        // 先到先排；同时钳到 now 时优先更近的档位（10 天优先于 30 天）。
                        .minWithOrNull(
                            compareBy<PlannedAlarm> { it.triggerAtMillis }
                                .thenBy { it.thresholdDays },
                        )
                if (next != null) add(next)
            }
        }
    }

    /**
     * 闹钟触发时再读当前快照：仍 Active、未刷满、且该档位触发日已到（含补发）。
     */
    fun decideFire(
        card: CardWithCount?,
        thresholdDays: Int,
        dueDateTokenFromIntent: Long,
        alreadyNotifiedForDue: Boolean,
        now: Instant,
        zoneId: ZoneId,
    ): FireDecision {
        if (card == null) {
            return FireDecision(
                shouldNotify = false,
                cardId = 0L,
                dueDateToken = dueDateTokenFromIntent,
                thresholdDays = thresholdDays,
                bank = "",
                name = "",
                currentCount = 0,
                requiredCount = 0,
                remainingDays = 0,
            )
        }
        val dueToken = card.card.nextDueDateMillis
        val cycle = card.cycle
        val remainingDays =
            if (dueToken != null) {
                val dueDate = DateToken.normalizeAnnualDate(DateToken.toLocalDate(dueToken))
                ChronoUnit.DAYS.between(now.atZone(zoneId).toLocalDate(), dueDate)
            } else {
                0L
            }
        val shouldNotify =
            !alreadyNotifiedForDue &&
                dueToken != null &&
                dueToken == dueDateTokenFromIntent &&
                cycle is AnnualFeeCycle.Active &&
                card.currentCount < card.card.requiredCount &&
                remainingDays <= thresholdDays &&
                remainingDays >= 0
        return FireDecision(
            shouldNotify = shouldNotify,
            cardId = card.card.id,
            dueDateToken = dueToken ?: dueDateTokenFromIntent,
            thresholdDays = thresholdDays,
            bank = card.card.bank,
            name = card.card.name,
            currentCount = card.currentCount,
            requiredCount = card.card.requiredCount,
            remainingDays = remainingDays,
        )
    }

    /** 测试辅助：固定 clock 的 plan。 */
    fun plan(
        cards: List<CardWithCount>,
        enabled: Boolean,
        clock: Clock,
        zoneId: ZoneId,
        alreadyNotified: (cardId: Long, thresholdDays: Int, dueDateToken: Long) -> Boolean =
            { _, _, _ -> false },
    ): List<PlannedAlarm> = plan(cards, enabled, clock.instant(), zoneId, alreadyNotified)
}
