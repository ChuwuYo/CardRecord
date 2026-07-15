package com.shuaji.cards.data

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId

enum class AnnualFeeCycleState {
    UNSCHEDULED,
    UPCOMING,
    ACTIVE,
    OVERDUE,
}

data class AnnualFeeCycle(
    val state: AnnualFeeCycleState,
    val startDate: LocalDate?,
    val startBoundaryMillis: Long?,
    val dueBoundaryMillis: Long?,
) {
    val canRecord: Boolean
        get() = state == AnnualFeeCycleState.UNSCHEDULED || state == AnnualFeeCycleState.ACTIVE

    val participatesInProgress: Boolean
        get() = canRecord

    fun includes(occurredAtMillis: Long): Boolean =
        when (state) {
            AnnualFeeCycleState.UNSCHEDULED -> true
            AnnualFeeCycleState.ACTIVE ->
                occurredAtMillis >= startBoundaryMillis!! && occurredAtMillis < dueBoundaryMillis!!
            AnnualFeeCycleState.UPCOMING,
            AnnualFeeCycleState.OVERDUE,
            -> false
        }

    companion object {
        fun resolve(
            nextDueDateToken: Long?,
            now: Instant,
            zoneId: ZoneId,
        ): AnnualFeeCycle {
            if (nextDueDateToken == null) {
                return AnnualFeeCycle(
                    state = AnnualFeeCycleState.UNSCHEDULED,
                    startDate = null,
                    startBoundaryMillis = null,
                    dueBoundaryMillis = null,
                )
            }

            val dueDate = DateToken.normalizeAnnualDate(DateToken.toLocalDate(nextDueDateToken))
            val startDate = previousAnnualDate(dueDate)
            val startBoundaryMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dueBoundaryMillis = dueDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val nowMillis = now.toEpochMilli()
            val state =
                when {
                    nowMillis >= dueBoundaryMillis -> AnnualFeeCycleState.OVERDUE
                    nowMillis < startBoundaryMillis -> AnnualFeeCycleState.UPCOMING
                    else -> AnnualFeeCycleState.ACTIVE
                }

            return AnnualFeeCycle(
                state = state,
                startDate = startDate,
                startBoundaryMillis = startBoundaryMillis,
                dueBoundaryMillis = dueBoundaryMillis,
            )
        }

        fun advanceDueDateUntilFuture(
            nextDueDateToken: Long,
            now: Instant,
            zoneId: ZoneId,
        ): Long {
            var dueDate = DateToken.normalizeAnnualDate(DateToken.toLocalDate(nextDueDateToken))
            while (!dueDate.atStartOfDay(zoneId).toInstant().isAfter(now)) {
                dueDate = nextAnnualDate(dueDate)
            }
            return DateToken.fromLocalDate(dueDate)
        }

        fun previousAnnualDate(date: LocalDate): LocalDate = annualDateInYear(date, date.year - 1)

        fun nextAnnualDate(date: LocalDate): LocalDate = annualDateInYear(date, date.year + 1)

        private fun annualDateInYear(
            date: LocalDate,
            targetYear: Int,
        ): LocalDate =
            if (date.month == Month.FEBRUARY && date.dayOfMonth >= 28) {
                val lastDay = YearMonth.of(targetYear, Month.FEBRUARY).lengthOfMonth()
                LocalDate.of(targetYear, Month.FEBRUARY, lastDay)
            } else {
                date.withYear(targetYear)
            }
    }
}
