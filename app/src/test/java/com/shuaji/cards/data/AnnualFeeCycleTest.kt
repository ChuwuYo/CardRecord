package com.shuaji.cards.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AnnualFeeCycleTest {
    private val utc = ZoneOffset.UTC

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

    private fun instant(text: String): Instant = Instant.parse(text)

    private fun token(date: LocalDate): Long = DateToken.fromLocalDate(date)
}
