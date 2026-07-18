package com.shuaji.cards.data

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset

object DateToken {
    private val utc: ZoneId = ZoneOffset.UTC

    fun toLocalDate(tokenMillis: Long): LocalDate = Instant.ofEpochMilli(tokenMillis).atZone(utc).toLocalDate()

    /** 把普通日历日期无损编码为 UTC 日期令牌，不附加任何业务归一化。 */
    fun fromLocalDate(date: LocalDate): Long = date.atStartOfDay(utc).toInstant().toEpochMilli()

    /** 年费结算日专用编码：2 月 28/29 日统一为当年二月月末。 */
    fun fromAnnualDate(date: LocalDate): Long = fromLocalDate(normalizeAnnualDate(date))

    fun normalizeAnnualDate(date: LocalDate): LocalDate =
        if (date.month == Month.FEBRUARY && date.dayOfMonth >= 28) {
            date.withDayOfMonth(date.lengthOfMonth())
        } else {
            date
        }

    fun format(tokenMillis: Long): String = toLocalDate(tokenMillis).toString()

    /** 年费结算日把 2 月 28/29 日统一显示为对应年份的二月月末。 */
    fun formatAnnualDue(tokenMillis: Long): String = normalizeAnnualDate(toLocalDate(tokenMillis)).toString()
}
