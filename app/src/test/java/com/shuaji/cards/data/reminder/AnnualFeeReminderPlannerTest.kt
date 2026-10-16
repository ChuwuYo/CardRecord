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
        val now = instant("2027-05-25T12:00:00Z")
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
