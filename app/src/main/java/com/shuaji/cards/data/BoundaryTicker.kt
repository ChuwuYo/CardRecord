package com.shuaji.cards.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private val ZONE_RECHECK_INTERVAL: Duration = Duration.ofMinutes(15)

private data class LocalBoundaryState(
    val date: LocalDate,
    val zoneId: ZoneId,
)

/**
 * 订阅时立即发射，本地日期或时区变化后再次发射。等待会按固定上限分段，因此即使系统没有
 * 给进程发送时区广播，也会在 15 分钟内重新读取当前时区。时区本身也是边界状态，避免同一本地日内
 * 切换时区后，依赖绝对时刻的年费窗口仍使用旧边界。
 */
fun localMidnightTicks(
    clock: Clock,
    zoneIdProvider: () -> ZoneId,
): Flow<Unit> =
    flow {
        var emittedState: LocalBoundaryState? = null
        while (true) {
            val now = clock.instant()
            val zone = zoneIdProvider()
            val localDate = now.atZone(zone).toLocalDate()
            val state = LocalBoundaryState(date = localDate, zoneId = zone)
            if (state != emittedState) {
                emit(Unit)
                emittedState = state
            }
            val nextMidnight =
                localDate
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
            val untilMidnight = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
            delay(untilMidnight.coerceAtMost(ZONE_RECHECK_INTERVAL.toMillis()))
        }
    }
