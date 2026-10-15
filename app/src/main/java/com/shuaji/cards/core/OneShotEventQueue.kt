package com.shuaji.cards.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class OneShotEventQueue<T> {
    private val channel = Channel<T>(Channel.BUFFERED)
    val events: Flow<T> = channel.receiveAsFlow()

    suspend fun emit(event: T) {
        channel.send(event)
    }
}
