package com.shuaji.cards.data

import android.content.Context
import com.shuaji.cards.data.local.AppDatabase

interface AppContainer {
    val repository: CardRepository
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
}
