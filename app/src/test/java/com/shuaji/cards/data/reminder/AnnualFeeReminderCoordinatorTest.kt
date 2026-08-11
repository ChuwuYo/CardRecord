package com.shuaji.cards.data.reminder

import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.CardWithCount
import com.shuaji.cards.data.DateToken
import com.shuaji.cards.data.local.CardEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 协调器对「无权限取消 / 有权限重排」的契约；不启动真实前台 Flow。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AnnualFeeReminderCoordinatorTest {
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2027-03-01T12:00:00Z")
    private val clock = Clock.fixed(now, utc)

    @Test
    fun applyPlan_cancelsWhenCannotPost() {
        val scheduler = mock<AnnualFeeReminderScheduler>()
        val notifier =
            mock<AnnualFeeReminderNotifier> {
                on { canPostNotifications() } doReturn false
            }
        val store = mock<AnnualFeeReminderStore>()
        val repository =
            mock<CardRepository> {
                on { observeCards() } doReturn flowOf(listOf(sampleCard()))
            }
        val coordinator =
            AnnualFeeReminderCoordinator(
                repository = repository,
                store = store,
                scheduler = scheduler,
                notifier = notifier,
                enabledFlow = flowOf(true),
                clock = clock,
                zoneIdProvider = { utc },
                foreground = MutableStateFlow(false),
            )

        coordinator.applyPlan(listOf(sampleCard()), enabled = true)

        verify(scheduler).cancelAllTracked()
        verify(scheduler, never()).replaceAll(any())
    }

    @Test
    fun applyPlan_schedulesWhenEnabledAndCanPost() {
        val scheduler = mock<AnnualFeeReminderScheduler>()
        val notifier =
            mock<AnnualFeeReminderNotifier> {
                on { canPostNotifications() } doReturn true
            }
        val store =
            mock<AnnualFeeReminderStore> {
                on { wasNotified(any(), any(), any()) } doReturn false
            }
        val repository =
            mock<CardRepository> {
                on { observeCards() } doReturn flowOf(listOf(sampleCard()))
            }
        val coordinator =
            AnnualFeeReminderCoordinator(
                repository = repository,
                store = store,
                scheduler = scheduler,
                notifier = notifier,
                enabledFlow = flowOf(true),
                clock = clock,
                zoneIdProvider = { utc },
                foreground = MutableStateFlow(false),
            )

        coordinator.applyPlan(listOf(sampleCard()), enabled = true)

        val captor = argumentCaptor<List<AnnualFeeReminderPlanner.PlannedAlarm>>()
        verify(scheduler, atLeastOnce()).replaceAll(captor.capture())
        assertEquals(1, captor.lastValue.size)
        assertEquals(30, captor.lastValue[0].thresholdDays)
    }

    @Test
    fun applyPlan_cancelsWhenDisabledEvenIfCanPost() {
        val scheduler = mock<AnnualFeeReminderScheduler>()
        val notifier =
            mock<AnnualFeeReminderNotifier> {
                on { canPostNotifications() } doReturn true
            }
        val coordinator =
            AnnualFeeReminderCoordinator(
                repository = mock(),
                store = mock(),
                scheduler = scheduler,
                notifier = notifier,
                enabledFlow = flowOf(false),
                clock = clock,
                zoneIdProvider = { utc },
                foreground = MutableStateFlow(false),
            )

        coordinator.applyPlan(listOf(sampleCard()), enabled = false)

        verify(scheduler).cancelAllTracked()
    }

    @Test
    fun shouldUseWhileIdle_nearUsesWhileIdle_farDoesNot() {
        val now = 1_000_000L
        assertTrue(
            AnnualFeeReminderScheduler.shouldUseWhileIdle(
                now + AnnualFeeReminderScheduler.WHILE_IDLE_WINDOW_MS,
                now,
            ),
        )
        assertTrue(
            !AnnualFeeReminderScheduler.shouldUseWhileIdle(
                now + AnnualFeeReminderScheduler.WHILE_IDLE_WINDOW_MS + 1,
                now,
            ),
        )
    }

    private fun sampleCard(): CardWithCount {
        val due = LocalDate.of(2027, 6, 1)
        val card =
            CardEntity(
                id = 7L,
                name = "金卡",
                bank = "测试银行",
                cardNumberMasked = "1234",
                nextDueDateMillis = DateToken.fromAnnualDate(due),
                requiredCount = 10,
                colorArgb = 0xFF112233.toInt(),
            )
        return CardWithCount(
            card = card,
            currentCount = 1,
            lastSwipeAtMillis = null,
            cycle = AnnualFeeCycle.resolve(card.nextDueDateMillis, now, utc),
            resolvedUserImageUri = null,
        )
    }
}
