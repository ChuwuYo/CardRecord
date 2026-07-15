package com.shuaji.cards.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class BoundaryTickerTest {
    @Test
    fun emitsImmediatelyAndAgainAtNextLocalMidnight() =
        runTest {
            val clock = SchedulerClock(Instant.parse("2027-06-01T23:59:59Z"), testScheduler)
            val values = mutableListOf<Unit>()
            val collection =
                launch {
                    localMidnightTicks(clock) { ZoneOffset.UTC }
                        .take(2)
                        .toList(values)
                }

            runCurrent()
            assertEquals(listOf(Unit), values)
            advanceTimeBy(999)
            runCurrent()
            assertEquals(listOf(Unit), values)
            advanceTimeBy(1)
            runCurrent()

            collection.join()
            assertEquals(listOf(Unit, Unit), values)
        }

    private class SchedulerClock(
        private val start: Instant,
        private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        private val clockZone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = clockZone

        override fun withZone(zone: ZoneId): Clock = SchedulerClock(start, scheduler, zone)

        override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
    }
}
