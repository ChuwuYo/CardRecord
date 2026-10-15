package com.shuaji.cards.data

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset

object DateToken {
    private val utc: ZoneId = ZoneOffset.UTC

    fun toLocalDate(tokenMillis: Long): LocalDate = Instant.ofEpochMilli(tokenMillis).atZone(utc).toLocalDate()

    fun fromLocalDate(date: LocalDate): Long = date.atStartOfDay(utc).toInstant().toEpochMilli()

    fun fromAnnualDate(date: LocalDate): Long = fromLocalDate(normalizeAnnualDate(date))

    fun normalizeAnnualDate(date: LocalDate): LocalDate =
        if (date.month == Month.FEBRUARY && date.dayOfMonth >= 28) {
            date.withDayOfMonth(date.lengthOfMonth())
        } else {
            date
        }

    fun annualDueDate(tokenMillis: Long): LocalDate = normalizeAnnualDate(toLocalDate(tokenMillis))
}
