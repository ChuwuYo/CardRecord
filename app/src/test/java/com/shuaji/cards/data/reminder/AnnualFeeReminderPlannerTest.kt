package com.shuaji.cards.data.reminder

import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardWithCount
import com.shuaji.cards.data.DateToken
import com.shuaji.cards.data.local.CardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AnnualFeeReminderPlannerTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun disabled_plansNothing() {
        val cards = listOf(activeUnmet(due = LocalDate.of(2027, 6, 1), now = "2027-04-01T12:00:00Z"))
        val plan =
            AnnualFeeReminderPlanner.plan(
                cards = cards,
                enabled = false,
                now = instant("2027-04-01T12:00:00Z"),
                zoneId = utc,
            )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun activeUnmet_schedulesNearestThresholdOnly() {
        val due = LocalDate.of(2027, 6, 1)
        val cards = listOf(activeUnmet(due = due, now = "2027-03-01T12:00:00Z"))
        val plan =
            AnnualFeeReminderPlanner.plan(
                cards = cards,
                enabled = true,
                now = instant("2027-03-01T12:00:00Z"),
                zoneId = utc,
            )
        assertEquals(1, plan.size)
        assertEquals(30, plan[0].thresholdDays)
        assertEquals(
            due
                .minusDays(30)
                .atStartOfDay(utc)
                .toInstant()
                .toEpochMilli(),
            plan[0].triggerAtMillis,
        )
    }

    @Test
    fun afterThirtyNotified_schedulesTenDayNext() {
        val due = LocalDate.of(2027, 6, 1)
        val token = DateToken.fromAnnualDate(due)
        val cards = listOf(activeUnmet(due = due, now = "2027-03-01T12:00:00Z"))
        val plan =
            AnnualFeeReminderPlanner.plan(
                cards = cards,
                enabled = true,
                now = instant("2027-03-01T12:00:00Z"),
                zoneId = utc,
                alreadyNotified = { cardId, threshold, dueToken ->
                    cardId == 7L && threshold == 30 && dueToken == token
                },
            )
        assertEquals(1, plan.size)
        assertEquals(10, plan[0].thresholdDays)
    }

    @Test
    fun pastThreshold_clampsTriggerToNow_prefersTenDay() {
        val due = LocalDate.of(2027, 6, 1)
        val now = instant("2027-05-25T12:00:00Z") // 距结算约 7 天，30/10 阈值均已过
        val cards = listOf(activeUnmet(due = due, now = "2027-05-25T12:00:00Z"))
        val plan =
            AnnualFeeReminderPlanner.plan(
                cards = cards,
                enabled = true,
                now = now,
                zoneId = utc,
            )
        assertEquals(1, plan.size)
        assertEquals(10, plan[0].thresholdDays)
        assertEquals(now.toEpochMilli(), plan[0].triggerAtMillis)
    }

    @Test
    fun afterCatchUpTenNotified_doesNotRescheduleThirty() {
        val due = LocalDate.of(2027, 6, 1)
        val token = DateToken.fromAnnualDate(due)
        val now = instant("2027-05-25T12:00:00Z")
        val plan =
            AnnualFeeReminderPlanner.plan(
                cards = listOf(activeUnmet(due = due, now = "2027-05-25T12:00:00Z")),
                enabled = true,
                now = now,
                zoneId = utc,
                alreadyNotified = { cardId, threshold, dueToken ->
                    cardId == 7L && threshold == 10 && dueToken == token
                },
            )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun alreadyNotifiedCatchUp_plansNothingForThatCard() {
        val due = LocalDate.of(2027, 6, 1)
        val token = DateToken.fromAnnualDate(due)
        val now = instant("2027-05-25T12:00:00Z")
        val plan =
            AnnualFeeReminderPlanner.plan(
                cards = listOf(activeUnmet(due = due, now = "2027-05-25T12:00:00Z")),
                enabled = true,
                now = now,
                zoneId = utc,
                alreadyNotified = { _, _, dueToken -> dueToken == token },
            )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun metQuota_plansNothing() {
        val due = LocalDate.of(2027, 6, 1)
        val card = baseCard(due).copy(requiredCount = 5)
        val item =
            CardWithCount(
                card = card,
                currentCount = 5,
                lastSwipeAtMillis = null,
                cycle =
                    AnnualFeeCycle.resolve(
                        card.nextDueDateMillis,
                        instant("2027-04-01T12:00:00Z"),
                        utc,
                    ),
                resolvedUserImageUri = null,
            )
        val plan =
            AnnualFeeReminderPlanner.plan(
                listOf(item),
                enabled = true,
                now = instant("2027-04-01T12:00:00Z"),
                zoneId = utc,
            )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun decideFire_notifiesWhenStillUnmetInsideThreshold() {
        val due = LocalDate.of(2027, 6, 1)
        val token = DateToken.fromAnnualDate(due)
        val card = activeUnmet(due = due, now = "2027-05-25T12:00:00Z")
        val decision =
            AnnualFeeReminderPlanner.decideFire(
                card = card,
                thresholdDays = 10,
                dueDateTokenFromIntent = token,
                alreadyNotifiedForDue = false,
                now = instant("2027-05-25T12:00:00Z"),
                zoneId = utc,
            )
        assertTrue(decision.shouldNotify)
        assertEquals(7L, decision.remainingDays)
    }

    @Test
    fun decideFire_skipsWhenAlreadyNotified() {
        val due = LocalDate.of(2027, 6, 1)
        val token = DateToken.fromAnnualDate(due)
        val card = activeUnmet(due = due, now = "2027-05-25T12:00:00Z")
        val decision =
            AnnualFeeReminderPlanner.decideFire(
                card = card,
                thresholdDays = 10,
                dueDateTokenFromIntent = token,
                alreadyNotifiedForDue = true,
                now = instant("2027-05-25T12:00:00Z"),
                zoneId = utc,
            )
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun decideFire_skipsWhenDueTokenMismatch() {
        val due = LocalDate.of(2027, 6, 1)
        val card = activeUnmet(due = due, now = "2027-05-25T12:00:00Z")
        val decision =
            AnnualFeeReminderPlanner.decideFire(
                card = card,
                thresholdDays = 10,
                dueDateTokenFromIntent = DateToken.fromAnnualDate(LocalDate.of(2028, 6, 1)),
                alreadyNotifiedForDue = false,
                now = instant("2027-05-25T12:00:00Z"),
                zoneId = utc,
            )
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun decideFire_skipsWhenMetQuota() {
        val due = LocalDate.of(2027, 6, 1)
        val token = DateToken.fromAnnualDate(due)
        val base = baseCard(due).copy(requiredCount = 3)
        val card =
            CardWithCount(
                card = base,
                currentCount = 3,
                lastSwipeAtMillis = null,
                cycle = AnnualFeeCycle.resolve(token, instant("2027-05-25T12:00:00Z"), utc),
                resolvedUserImageUri = null,
            )
        val decision =
            AnnualFeeReminderPlanner.decideFire(
                card = card,
                thresholdDays = 10,
                dueDateTokenFromIntent = token,
                alreadyNotifiedForDue = false,
                now = instant("2027-05-25T12:00:00Z"),
                zoneId = utc,
            )
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun decideFire_skipsWhenStillOutsideThreshold() {
        val due = LocalDate.of(2027, 6, 1)
        val token = DateToken.fromAnnualDate(due)
        val card = activeUnmet(due = due, now = "2027-04-01T12:00:00Z") // ~61 days left
        val decision =
            AnnualFeeReminderPlanner.decideFire(
                card = card,
                thresholdDays = 10,
                dueDateTokenFromIntent = token,
                alreadyNotifiedForDue = false,
                now = instant("2027-04-01T12:00:00Z"),
                zoneId = utc,
            )
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun decideFire_skipsNullCard() {
        val decision =
            AnnualFeeReminderPlanner.decideFire(
                card = null,
                thresholdDays = 10,
                dueDateTokenFromIntent = 1L,
                alreadyNotifiedForDue = false,
                now = instant("2027-05-25T12:00:00Z"),
                zoneId = utc,
            )
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun preExistingActiveCards_allIncludedWhenRemindersEnabled() {
        // 模拟升级前库里已有的多张 Active 未刷满卡：开启提醒后应全部进入计划。
        val now = "2027-03-01T12:00:00Z"
        val cards =
            listOf(
                activeUnmet(due = LocalDate.of(2027, 6, 1), now = now).let {
                    it.copy(card = it.card.copy(id = 11L, name = "旧卡甲"))
                },
                activeUnmet(due = LocalDate.of(2027, 8, 15), now = now).let {
                    it.copy(card = it.card.copy(id = 22L, name = "旧卡乙"))
                },
                // 升级前未设结算日的卡：不得进入提醒计划
                CardWithCount(
                    card =
                        baseCard(LocalDate.of(2027, 6, 1)).copy(
                            id = 33L,
                            name = "无结算日",
                            nextDueDateMillis = null,
                        ),
                    currentCount = 0,
                    lastSwipeAtMillis = null,
                    cycle = AnnualFeeCycle.Unscheduled,
                    resolvedUserImageUri = null,
                ),
            )
        val plan =
            AnnualFeeReminderPlanner.plan(
                cards = cards,
                enabled = true,
                now = instant(now),
                zoneId = utc,
            )
        assertEquals(2, plan.size) // 两张 Active × 各一档（下一档）
        assertEquals(setOf(11L, 22L), plan.map { it.cardId }.toSet())
        assertFalse(plan.any { it.cardId == 33L })
    }

    @Test
    fun preExistingCardAlreadyInsideWindow_clampsToNowForCatchUp() {
        // 升级时卡已进入 10 天窗口：开启提醒后应立刻可补发，而不是丢弃。
        val due = LocalDate.of(2027, 6, 1)
        val now = instant("2027-05-25T12:00:00Z")
        val plan =
            AnnualFeeReminderPlanner.plan(
                cards = listOf(activeUnmet(due = due, now = "2027-05-25T12:00:00Z")),
                enabled = true,
                now = now,
                zoneId = utc,
            )
        assertEquals(1, plan.size)
        assertEquals(10, plan[0].thresholdDays)
        assertEquals(now.toEpochMilli(), plan[0].triggerAtMillis)
    }

    private fun activeUnmet(
        due: LocalDate,
        now: String,
    ): CardWithCount {
        val card = baseCard(due)
        return CardWithCount(
            card = card,
            currentCount = 1,
            lastSwipeAtMillis = null,
            cycle = AnnualFeeCycle.resolve(card.nextDueDateMillis, instant(now), utc),
            resolvedUserImageUri = null,
        )
    }

    private fun baseCard(due: LocalDate): CardEntity =
        CardEntity(
            id = 7L,
            name = "金卡",
            bank = "测试银行",
            cardNumberMasked = "1234",
            nextDueDateMillis = DateToken.fromAnnualDate(due),
            requiredCount = 10,
            colorArgb = 0xFF112233.toInt(),
        )

    private fun instant(iso: String): Instant = Instant.parse(iso)
}
