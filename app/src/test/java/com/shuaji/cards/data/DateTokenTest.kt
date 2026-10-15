package com.shuaji.cards.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DateTokenTest {
    @Test
    fun utcToken_doesNotChangeCalendarDateAcrossZones() {
        val token = DateToken.fromLocalDate(LocalDate.of(2026, 7, 15))

        assertEquals(LocalDate.of(2026, 7, 15), DateToken.toLocalDate(token))
    }

    @Test
    fun ordinaryDateToken_preservesLeapYearFebruary28Exactly() {
        val token = DateToken.fromLocalDate(LocalDate.of(2028, 2, 28))

        assertEquals(LocalDate.of(2028, 2, 28), DateToken.toLocalDate(token))
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

    @Test
    fun annualDueToken_leapYearFebruary28IsReadAsMonthEnd() {
        val legacyToken =
            LocalDate
                .of(2028, 2, 28)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        assertEquals(LocalDate.of(2028, 2, 29), DateToken.annualDueDate(legacyToken))
        assertEquals(LocalDate.of(2028, 2, 28), DateToken.toLocalDate(legacyToken))
    }
}
