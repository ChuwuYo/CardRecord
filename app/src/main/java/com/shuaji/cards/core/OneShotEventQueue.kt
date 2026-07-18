package com.shuaji.cards.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** 暂存尚未展示的一次性事件；短暂无 collector 时不丢失，且每条只交付给一个 collector。 */
class OneShotEventQueue<T> {
    private val channel = Channel<T>(Channel.BUFFERED)
    val events: Flow<T> = channel.receiveAsFlow()

    suspend fun emit(event: T) {
        channel.send(event)
    }
}
