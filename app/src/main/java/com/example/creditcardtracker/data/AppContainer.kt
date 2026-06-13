package com.example.creditcardtracker.data

import android.content.Context
import com.example.creditcardtracker.data.local.AppDatabase

interface AppContainer {
    val repository: CreditCardRepository
}

class DefaultAppContainer(
    context: Context,
) : AppContainer {
    private val database = AppDatabase.get(context)
    override val repository: CreditCardRepository =
        CreditCardRepository(
            cardDao = database.creditCardDao(),
            transactionDao = database.transactionDao(),
        )
}
