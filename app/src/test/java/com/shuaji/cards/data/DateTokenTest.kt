package com.shuaji.cards.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DateTokenTest {
    @Test
    fun utcToken_doesNotChangeCalendarDateAcrossZones() {
        val token = DateToken.fromLocalDate(LocalDate.of(2026, 7, 15))

        assertEquals(LocalDate.of(2026, 7, 15), DateToken.toLocalDate(token))
    }

    @Test
    fun annualDate_normalizesLeapYearFebruary28ToMonthEnd() {
        assertEquals(
            LocalDate.of(2028, 2, 29),
            DateToken.normalizeAnnualDate(LocalDate.of(2028, 2, 28)),
        )
        assertEquals(
            LocalDate.of(2028, 2, 29),
            DateToken.toLocalDate(DateToken.fromAnnualDate(LocalDate.of(2028, 2, 28))),
        )
    }
}
