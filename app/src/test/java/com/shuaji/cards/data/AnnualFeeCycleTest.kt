package com.shuaji.cards.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class AnnualFeeCycleTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun dueTwoYearsAway_isUpcomingUntilPreviousAnniversary() {
        val due = token(LocalDate.of(2028, 6, 1))

        assertTrue(AnnualFeeCycle.resolve(due, instant("2026-06-01T00:00:00Z"), utc) is AnnualFeeCycle.Upcoming)
        assertTrue(AnnualFeeCycle.resolve(due, instant("2027-06-01T00:00:00Z"), utc) is AnnualFeeCycle.Active)
    }

    @Test
    fun activeWindow_isStartInclusiveAndDueExclusive() {
        val cycle =
            AnnualFeeCycle.resolve(
                token(LocalDate.of(2028, 6, 1)),
                instant("2027-06-01T00:00:00Z"),
                utc,
            ) as AnnualFeeCycle.Active

        assertTrue(cycle.includes(instant("2027-06-01T00:00:00Z").toEpochMilli()))
        assertTrue(cycle.includes(instant("2028-05-31T23:59:59.999Z").toEpochMilli()))
        assertFalse(cycle.includes(instant("2028-06-01T00:00:00Z").toEpochMilli()))
    }

    @Test
    fun februaryMonthEnd_isReversible() {
        assertEquals(
            LocalDate.of(2028, 2, 29),
            AnnualFeeCycle.previousAnnualDate(LocalDate.of(2029, 2, 28)),
        )
    }

    @Test
    fun nullDue_isUnscheduledAndIncludesAllTransactions() {
        val cycle = AnnualFeeCycle.resolve(null, instant("2027-01-01T00:00:00Z"), utc)

        assertTrue(cycle is AnnualFeeCycle.Unscheduled)
        assertTrue(cycle.canRecord)
        assertTrue(cycle.participatesInProgress)
        assertTrue(cycle.includes(0L))
    }

    @Test
    fun dueAtNow_isOverdue() {
        val cycle =
            AnnualFeeCycle.resolve(
                token(LocalDate.of(2027, 1, 1)),
                instant("2027-01-01T00:00:00Z"),
                utc,
            )

        assertTrue(cycle is AnnualFeeCycle.Overdue)
        assertFalse(cycle.canRecord)
        assertFalse(cycle.participatesInProgress)
        assertFalse(cycle.includes(instant("2026-06-01T00:00:00Z").toEpochMilli()))
    }

    @Test
    fun multipleOverdueYears_advanceToFirstFutureBoundary() {
        val advanced =
            AnnualFeeCycle.advanceDueDateUntilFuture(
                token(LocalDate.of(2024, 1, 1)),
                instant("2027-06-01T00:00:00Z"),
                utc,
            )

        assertEquals(LocalDate.of(2028, 1, 1), DateToken.toLocalDate(advanced))
    }

    @Test
    fun advanceAcrossManyYears_preservesAnnualRuleWithoutIterationState() {
        val advanced =
            AnnualFeeCycle.advanceDueDateUntilFuture(
                token(LocalDate.of(1900, 2, 28)),
                instant("9999-02-28T00:00:00Z"),
                utc,
            )

        assertEquals(LocalDate.of(10000, 2, 29), DateToken.toLocalDate(advanced))
    }

    @Test
    fun localStartOfDay_definesAbsoluteWindowBoundaries() {
        val seoul = ZoneId.of("Asia/Seoul")
        val cycle =
            AnnualFeeCycle.resolve(
                token(LocalDate.of(2028, 6, 1)),
                instant("2027-05-31T15:00:00Z"),
                seoul,
            ) as AnnualFeeCycle.Active

        assertEquals(instant("2027-05-31T15:00:00Z").toEpochMilli(), cycle.startBoundaryMillis)
        assertEquals(instant("2028-05-31T15:00:00Z").toEpochMilli(), cycle.dueBoundaryMillis)
    }

    @Test
    fun upcomingCycle_doesNotRecordOrParticipateInProgress() {
        val cycle =
            AnnualFeeCycle.resolve(
                token(LocalDate.of(2028, 6, 1)),
                instant("2027-05-31T23:59:59.999Z"),
                utc,
            )

        assertTrue(cycle is AnnualFeeCycle.Upcoming)
        assertFalse(cycle.canRecord)
        assertFalse(cycle.participatesInProgress)
        assertFalse(cycle.includes(instant("2027-06-01T00:00:00Z").toEpochMilli()))
    }

    private fun instant(text: String): Instant = Instant.parse(text)

    private fun token(date: LocalDate): Long = DateToken.fromLocalDate(date)
}
