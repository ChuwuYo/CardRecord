package com.shuaji.cards.ui.screen

import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.AnnualFeeCycleState
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
                    cardUi(AnnualFeeCycleState.ACTIVE, 8, 5),
                    cardUi(AnnualFeeCycleState.UPCOMING, 0, 9),
                    cardUi(AnnualFeeCycleState.ACTIVE, 7, 0),
                ),
            )

        assertEquals(OverallProgress(5, 5, 100, allDone = true, isEmpty = false), result)
    }

    @Test
    fun overallProgress_withNoParticipants_isEmptyNotDone() {
        assertEquals(
            OverallProgress(0, 0, 0, allDone = false, isEmpty = true),
            calculateOverallProgress(listOf(cardUi(AnnualFeeCycleState.UPCOMING, 0, 5))),
        )
    }

    @Test
    fun grouping_placesUpcomingAfterActiveCards() {
        val sorted =
            sortCardsForOverall(
                listOf(
                    cardUi(AnnualFeeCycleState.UPCOMING, 0, 5),
                    cardUi(AnnualFeeCycleState.ACTIVE, 1, 5),
                ),
            )

        assertEquals(listOf(AnnualFeeCycleState.ACTIVE, AnnualFeeCycleState.UPCOMING), sorted.map { it.cycle.state })
    }

    @Test
    fun overdueAndUpcoming_sortAfterEveryActiveCard() {
        val sorted =
            sortCardsForOverall(
                listOf(
                    cardUi(AnnualFeeCycleState.OVERDUE, 0, 5),
                    cardUi(AnnualFeeCycleState.UPCOMING, 0, 5),
                    cardUi(AnnualFeeCycleState.ACTIVE, 8, 5),
                ),
            )

        assertEquals(
            listOf(AnnualFeeCycleState.ACTIVE, AnnualFeeCycleState.OVERDUE, AnnualFeeCycleState.UPCOMING),
            sorted.map { it.cycle.state },
        )
    }

    private fun cardUi(
        state: AnnualFeeCycleState,
        count: Int,
        required: Int,
    ): CardUi =
        CardUi(
            card =
                CardEntity(
                    name = state.name,
                    bank = "",
                    cardNumberMasked = "",
                    requiredCount = required,
                    colorArgb = 0,
                    createdAtMillis = state.ordinal.toLong(),
                ),
            currentCount = count,
            isExpired = false,
            lastSwipeAtMillis = null,
            cycle = cycle(state),
        )

    private fun cycle(state: AnnualFeeCycleState): AnnualFeeCycle =
        when (state) {
            AnnualFeeCycleState.UNSCHEDULED -> AnnualFeeCycle(state, null, null, null)
            else -> {
                val start = LocalDate.of(2027, 6, 1)
                AnnualFeeCycle(
                    state = state,
                    startDate = start,
                    startBoundaryMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    dueBoundaryMillis =
                        start
                            .plusYears(1)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant()
                            .toEpochMilli(),
                )
            }
        }
}
