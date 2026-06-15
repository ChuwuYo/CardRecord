package com.shuaji.cards.data

import android.content.Context
import com.shuaji.cards.data.local.AppDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

interface AppContainer {
    val repository: CardRepository
    val settings: SettingsRepository

    /**
     * 自动续期事件：值 = 本次启动时续期的卡数。
     * Application.onCreate 跑 [CardRepository.resetOverdueCycles]，结果 emit 到这里；
     * UI 层订阅后弹 Snackbar 告知用户。值 = 0 不发事件（避免噪音）。
     */
    val cycleAutoResetEvents: SharedFlow<Int>
}

class DefaultAppContainer(
    context: Context,
) : AppContainer {
    private val database = AppDatabase.get(context)
    override val repository: CardRepository =
        CardRepository(
            cardDao = database.cardDao(),
            transactionDao = database.transactionDao(),
            folderDao = database.cardFolderDao(),
        )
    override val settings: SettingsRepository = SettingsRepository(context.appDataStore)

    private val _cycleAutoResetEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    override val cycleAutoResetEvents: SharedFlow<Int> = _cycleAutoResetEvents.asSharedFlow()

    /** ShuajiApplication 启动时调一次：把仓库结果 emit 到 SharedFlow。 */
    suspend fun emitCycleAutoReset(count: Int) {
        if (count > 0) _cycleAutoResetEvents.emit(count)
    }
}
