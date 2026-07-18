package com.shuaji.cards.ui.screen

import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.local.CardEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class OverallProgressTest {
    @Test
    fun overallProgress_excludesUpcomingAndZeroTargetCards() {
        val result =
            calculateOverallProgress(
                listOf(
                    cardUi(activeCycle(), 8, 5),
                    cardUi(AnnualFeeCycle.Upcoming(LocalDate.of(2027, 6, 1)), 0, 9),
                    cardUi(activeCycle(), 7, 0),
                ),
            )

        assertEquals(OverallProgress(5L, 5L, 100, allDone = true, isEmpty = false), result)
    }

    @Test
    fun overallProgress_withNoParticipants_isEmptyNotDone() {
        assertEquals(
            OverallProgress(0L, 0L, 0, allDone = false, isEmpty = true),
            calculateOverallProgress(listOf(cardUi(AnnualFeeCycle.Upcoming(LocalDate.of(2027, 6, 1)), 0, 5))),
        )
    }

    @Test
    fun overallProgress_usesLongAggregationWithoutOverflow() {
        val result =
            calculateOverallProgress(
                listOf(
                    cardUi(activeCycle(), Int.MAX_VALUE, Int.MAX_VALUE),
                    cardUi(activeCycle(), Int.MAX_VALUE, Int.MAX_VALUE),
                ),
            )

        assertEquals(Int.MAX_VALUE.toLong() * 2L, result.current)
        assertEquals(Int.MAX_VALUE.toLong() * 2L, result.required)
        assertEquals(100, result.percent)
    }

    @Test
    fun grouping_placesUpcomingAfterActiveCards() {
        val sorted =
            sortCardsForOverall(
                listOf(
                    cardUi(AnnualFeeCycle.Upcoming(LocalDate.of(2027, 6, 1)), 0, 5),
                    cardUi(activeCycle(), 1, 5),
                ),
            )

        assertEquals(listOf("active", "upcoming"), sorted.map { it.cycle.label() })
    }

    @Test
    fun overdueAndUpcoming_sortAfterEveryActiveCard() {
        val sorted =
            sortCardsForOverall(
                listOf(
                    cardUi(AnnualFeeCycle.Overdue, 0, 5),
                    cardUi(AnnualFeeCycle.Upcoming(LocalDate.of(2027, 6, 1)), 0, 5),
                    cardUi(activeCycle(), 8, 5),
                ),
            )

        assertEquals(
            listOf("active", "overdue", "upcoming"),
            sorted.map { it.cycle.label() },
        )
    }

    private fun cardUi(
        cycle: AnnualFeeCycle,
        count: Int,
        required: Int,
    ): CardUi =
        CardUi(
            card =
                CardEntity(
                    name = cycle.label(),
                    bank = "",
                    cardNumberMasked = "",
                    requiredCount = required,
                    colorArgb = 0,
                    createdAtMillis = 1L,
                ),
            currentCount = count,
            isExpired = false,
            lastSwipeAtMillis = null,
            cycle = cycle,
        )

    private fun activeCycle(): AnnualFeeCycle.Active {
        val start = LocalDate.of(2027, 6, 1)
        return AnnualFeeCycle.Active(
            startBoundaryMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            dueBoundaryMillis =
                start
                    .plusYears(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
        )
    }

    private fun AnnualFeeCycle.label(): String =
        when (this) {
            AnnualFeeCycle.Unscheduled -> "unscheduled"
            is AnnualFeeCycle.Upcoming -> "upcoming"
            is AnnualFeeCycle.Active -> "active"
            AnnualFeeCycle.Overdue -> "overdue"
        }
}
