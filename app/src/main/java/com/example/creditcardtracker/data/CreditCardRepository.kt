package com.example.creditcardtracker.data

import com.example.creditcardtracker.data.local.CreditCardDao
import com.example.creditcardtracker.data.local.CreditCardEntity
import com.example.creditcardtracker.data.local.TransactionDao
import com.example.creditcardtracker.data.local.TransactionEntity
import kotlinx.coroutines.flow.Flow

class CreditCardRepository(
    private val cardDao: CreditCardDao,
    private val transactionDao: TransactionDao,
) {
    fun observeCards(): Flow<List<CreditCardEntity>> = cardDao.observeActive()

    fun observeCard(id: Long): Flow<CreditCardEntity?> = cardDao.observeById(id)

    fun observeTransactions(cardId: Long): Flow<List<TransactionEntity>> = transactionDao.observeForCard(cardId)

    suspend fun upsertCard(card: CreditCardEntity): Long = cardDao.upsert(card)

    suspend fun updateCard(card: CreditCardEntity) = cardDao.update(card)

    suspend fun deleteCard(card: CreditCardEntity) = cardDao.delete(card)

    suspend fun archiveCard(
        id: Long,
        archived: Boolean,
    ) = cardDao.setArchived(id, archived)

    /**
     * 增加一次消费并自动同步 [CreditCardEntity.currentCount]。
     * 返回是否成功落库。
     */
    suspend fun recordTransaction(transaction: TransactionEntity): Long {
        val id = transactionDao.insert(transaction)
        val card = cardDao.getById(transaction.cardId) ?: return id
        val newCount = (card.currentCount + 1).coerceAtMost(card.requiredCount)
        cardDao.setCurrentCount(card.id, newCount)
        return id
    }

    suspend fun deleteTransaction(transaction: TransactionEntity) {
        transactionDao.delete(transaction)
        val card = cardDao.getById(transaction.cardId) ?: return
        val newCount = (card.currentCount - 1).coerceAtLeast(0)
        cardDao.setCurrentCount(card.id, newCount)
    }

    suspend fun resetCycle(id: Long) {
        cardDao.resetCycle(id, System.currentTimeMillis())
    }

    suspend fun syncCountFromTransactions(cardId: Long) {
        val count = transactionDao.countForCard(cardId)
        val card = cardDao.getById(cardId) ?: return
        cardDao.setCurrentCount(cardId, count.coerceAtMost(card.requiredCount))
    }
}
