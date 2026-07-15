package com.shuaji.cards.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

/**
 * 订阅时立即发射，并在设备当前时区的每个下一个本地零时再次发射。
 * 每轮都重新读取时区，前台持续运行期间也能响应用户切换系统时区。
 */
fun localMidnightTicks(
    clock: Clock,
    zoneIdProvider: () -> ZoneId,
): Flow<Unit> =
    flow {
        while (true) {
            emit(Unit)
            val now = clock.instant()
            val zone = zoneIdProvider()
            val nextMidnight =
                now
                    .atZone(zone)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L))
        }
    }
