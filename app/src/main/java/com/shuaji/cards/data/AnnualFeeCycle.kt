package com.shuaji.cards.data

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max

/**
 * 年费统计周期。不同状态只携带该状态确实存在的数据，调用方无需靠非空断言补偿非法组合。
 */
sealed interface AnnualFeeCycle {
    val canRecord: Boolean
        get() = this is Unscheduled || this is Active

    val participatesInProgress: Boolean
        get() = canRecord

    fun includes(occurredAtMillis: Long): Boolean =
        when (this) {
            Unscheduled -> true
            is Active -> occurredAtMillis >= startBoundaryMillis && occurredAtMillis < dueBoundaryMillis
            is Upcoming,
            Overdue,
            -> false
        }

    data object Unscheduled : AnnualFeeCycle

    data class Upcoming(
        val startDate: LocalDate,
    ) : AnnualFeeCycle

    data class Active(
        val startBoundaryMillis: Long,
        val dueBoundaryMillis: Long,
    ) : AnnualFeeCycle {
        init {
            require(startBoundaryMillis < dueBoundaryMillis) { "年费周期起点必须早于结算边界" }
        }
    }

    data object Overdue : AnnualFeeCycle

    companion object {
        fun resolve(
            nextDueDateToken: Long?,
            now: Instant,
            zoneId: ZoneId,
        ): AnnualFeeCycle {
            if (nextDueDateToken == null) return Unscheduled

            val dueDate = DateToken.normalizeAnnualDate(DateToken.toLocalDate(nextDueDateToken))
            val startDate = previousAnnualDate(dueDate)
            val startBoundaryMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dueBoundaryMillis = dueDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val nowMillis = now.toEpochMilli()
            return when {
                nowMillis >= dueBoundaryMillis -> Overdue
                nowMillis < startBoundaryMillis -> Upcoming(startDate)
                else -> Active(startBoundaryMillis, dueBoundaryMillis)
            }
        }

        fun advanceDueDateUntilFuture(
            nextDueDateToken: Long,
            now: Instant,
            zoneId: ZoneId,
        ): Long {
            val dueDate = DateToken.normalizeAnnualDate(DateToken.toLocalDate(nextDueDateToken))
            val targetYear = max(dueDate.year, now.atZone(zoneId).year)
            var candidate = annualDateInYear(dueDate, targetYear)
            if (!candidate.atStartOfDay(zoneId).toInstant().isAfter(now)) {
                candidate = annualDateInYear(dueDate, targetYear + 1)
            }
            return DateToken.fromAnnualDate(candidate)
        }

        fun previousAnnualDate(date: LocalDate): LocalDate = annualDateInYear(date, date.year - 1)

        private fun annualDateInYear(
            date: LocalDate,
            targetYear: Int,
        ): LocalDate =
            if (date.month == Month.FEBRUARY && date.dayOfMonth >= 28) {
                LocalDate.of(targetYear, Month.FEBRUARY, YearMonth.of(targetYear, Month.FEBRUARY).lengthOfMonth())
            } else {
                date.withYear(targetYear)
            }
    }
}
