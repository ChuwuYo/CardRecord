package com.shuaji.cards.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.shuaji.cards.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnualFeeCycleCoordinatorTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun queuedEvent_isDeliveredOnceAndNotReplayedToLaterCollector() =
        runTest {
            val queue = AnnualFeeCycleEventQueue()
            val event = AnnualFeeCycleEvent.Failed(IllegalStateException("旧失败"))
            queue.emit(event)

            val first =
                queue.events
                    .take(1)
                    .toList()
                    .single()
            val second =
                withTimeoutOrNull(1) {
                    queue.events
                        .take(1)
                        .toList()
                        .single()
                }
            assertEquals(event, first)
            assertNull(second)
        }

    @Test
    fun processLifecycle_startAndStopDriveForegroundFlow() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val owner = TestLifecycleOwner()
            val values = mutableListOf<Boolean>()
            val collection =
                launch {
                    processForegroundFlow(owner.lifecycle)
                        .take(3)
                        .toList(values)
                }

            runCurrent()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            runCurrent()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            runCurrent()

            collection.join()
            assertEquals(listOf(false, true, false), values)
        }

    @Test
    fun processLifecycle_cancelledCollectionRemovesObserver() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val owner = TestLifecycleOwner()
            val values = mutableListOf<Boolean>()
            val collection =
                launch {
                    processForegroundFlow(owner.lifecycle).toList(values)
                }

            runCurrent()
            assertEquals(1, owner.registry.observerCount)
            collection.cancelAndJoin()
            runCurrent()
            assertEquals(0, owner.registry.observerCount)

            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            runCurrent()
            assertEquals(listOf(false), values)
        }

    @Test
    fun boundaryTick_normalizesOverdueCycle() =
        runTest {
            var normalizeCalls = 0
            val coordinator =
                AnnualFeeCycleCoordinator(
                    normalize = { ++normalizeCalls },
                    boundaryTicks = flowOf(Unit),
                    foreground = flowOf(true),
                    onEvent = {},
                )

            coordinator.start(backgroundScope)
            runCurrent()

            assertEquals(1, normalizeCalls)
        }

    @Test
    fun transientFailure_retriesAfterThirtySecondsWhileForeground() =
        runTest {
            var normalizeCalls = 0
            val events = mutableListOf<AnnualFeeCycleEvent>()
            val coordinator =
                AnnualFeeCycleCoordinator(
                    normalize = {
                        normalizeCalls += 1
                        if (normalizeCalls == 1) error("暂时失败")
                        1
                    },
                    boundaryTicks = flowOf(Unit),
                    foreground = flowOf(true),
                    onEvent = events::add,
                )

            coordinator.start(backgroundScope)
            runCurrent()
            assertEquals(1, normalizeCalls)
            assertTrue(events.single() is AnnualFeeCycleEvent.Failed)
            advanceTimeBy(30_000)
            runCurrent()

            assertEquals(2, normalizeCalls)
            assertEquals(AnnualFeeCycleEvent.Normalized(1), events.last())
        }

    @Test
    fun leavingForeground_cancelsPendingRetry() =
        runTest {
            var normalizeCalls = 0
            val foreground = MutableStateFlow(true)
            val coordinator =
                AnnualFeeCycleCoordinator(
                    normalize = {
                        normalizeCalls += 1
                        error("暂时失败")
                    },
                    boundaryTicks = flowOf(Unit),
                    foreground = foreground,
                    onEvent = {},
                )

            coordinator.start(backgroundScope)
            runCurrent()
            foreground.value = false
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertEquals(1, normalizeCalls)
        }

    @Test
    fun returningToForeground_retriesImmediately() =
        runTest {
            var normalizeCalls = 0
            val foreground = MutableStateFlow(false)
            val ticks = MutableSharedFlow<Unit>()
            val coordinator =
                AnnualFeeCycleCoordinator(
                    normalize = { ++normalizeCalls },
                    boundaryTicks = ticks,
                    foreground = foreground,
                    onEvent = {},
                )

            coordinator.start(backgroundScope)
            runCurrent()
            foreground.value = true
            runCurrent()
            ticks.emit(Unit)
            runCurrent()

            assertEquals(1, normalizeCalls)
        }

    @Test
    fun cancellation_isRethrownWithoutErrorEvent() =
        runTest {
            val events = mutableListOf<AnnualFeeCycleEvent>()
            val coordinator =
                AnnualFeeCycleCoordinator(
                    normalize = { awaitCancellation() },
                    boundaryTicks = flowOf(Unit),
                    foreground = flowOf(true),
                    onEvent = events::add,
                )

            val job = coordinator.start(backgroundScope)
            runCurrent()
            job.cancel(CancellationException("测试取消"))
            runCurrent()

            assertTrue(job.isCancelled)
            assertEquals(emptyList<AnnualFeeCycleEvent>(), events)
        }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }
}
