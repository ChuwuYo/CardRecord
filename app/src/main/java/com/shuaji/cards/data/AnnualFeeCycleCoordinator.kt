package com.shuaji.cards.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event.ON_START
import androidx.lifecycle.Lifecycle.Event.ON_STOP
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.shuaji.cards.core.OneShotEventQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface AnnualFeeCycleEvent {
    data class Normalized(
        val count: Int,
    ) : AnnualFeeCycleEvent

    data class Failed(
        val error: Throwable,
    ) : AnnualFeeCycleEvent
}

typealias AnnualFeeCycleEventQueue = OneShotEventQueue<AnnualFeeCycleEvent>

/** 在进程前台期间，按订阅首发与本地零时边界推进过期年费周期。 */
class AnnualFeeCycleCoordinator(
    private val normalize: suspend () -> Int,
    private val boundaryTicks: Flow<Unit>,
    private val foreground: Flow<Boolean>,
    private val onEvent: suspend (AnnualFeeCycleEvent) -> Unit,
    private val retryDelayMillis: Long = 30_000L,
) {
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            foreground
                .distinctUntilChanged()
                .collectLatest { isForeground ->
                    if (isForeground) {
                        boundaryTicks.collectLatest {
                            while (currentCoroutineContext().isActive) {
                                try {
                                    val normalizedCount = normalize()
                                    if (normalizedCount > 0) {
                                        onEvent(AnnualFeeCycleEvent.Normalized(normalizedCount))
                                    }
                                    break
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    onEvent(AnnualFeeCycleEvent.Failed(error))
                                    delay(retryDelayMillis)
                                }
                            }
                        }
                    }
                }
        }
}

/** 把进程级 START / STOP 生命周期转换为可注入的前台状态流。 */
fun processForegroundFlow(lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle): Flow<Boolean> =
    callbackFlow {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    ON_START -> trySend(true)
                    ON_STOP -> trySend(false)
                    else -> Unit
                }
            }
        trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        awaitClose { lifecycle.removeObserver(observer) }
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Main.immediate)
