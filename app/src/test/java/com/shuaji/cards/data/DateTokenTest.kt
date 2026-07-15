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
    fun leapYearFebruary28_normalizesToMonthEnd() {
        assertEquals(
            LocalDate.of(2028, 2, 29),
            DateToken.normalizeAnnualDate(LocalDate.of(2028, 2, 28)),
        )
    }

    @Test
    fun dateToken_isFormattedAsIsoCalendarDate() {
        val token = DateToken.fromLocalDate(LocalDate.of(2026, 12, 3))

        assertEquals("2026-12-03", DateToken.format(token))
    }
}
