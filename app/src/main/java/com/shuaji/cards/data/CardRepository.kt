package com.shuaji.cards.data

import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.TransactionDao
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.flow.Flow

class CardRepository(
    private val cardDao: CardDao,
    private val transactionDao: TransactionDao,
    private val folderDao: CardFolderDao,
) {
    fun observeCards(): Flow<List<CardEntity>> = cardDao.observeActive()

    fun observeCard(id: Long): Flow<CardEntity?> = cardDao.observeById(id)

    suspend fun getCard(id: Long): CardEntity? = cardDao.getById(id)

    fun observeTransactions(cardId: Long): Flow<List<TransactionEntity>> = transactionDao.observeForCard(cardId)

    suspend fun upsertCard(card: CardEntity): Long = cardDao.upsert(card)

    suspend fun updateCard(card: CardEntity) = cardDao.update(card)

    suspend fun deleteCard(card: CardEntity) = cardDao.delete(card)

    suspend fun archiveCard(
        id: Long,
        archived: Boolean,
    ) = cardDao.setArchived(id, archived)

    /**
     * 增加一次消费并自动同步 [CardEntity.currentCount]。
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

    // ── 文件夹 ──

    fun observeFolders(): Flow<List<CardFolderEntity>> = folderDao.observeAll()

    suspend fun getFolder(id: Long): CardFolderEntity? = folderDao.getById(id)

    suspend fun upsertFolder(folder: CardFolderEntity): Long = folderDao.upsert(folder)

    suspend fun updateFolder(folder: CardFolderEntity) = folderDao.update(folder)

    suspend fun deleteFolder(folder: CardFolderEntity) {
        // 删除文件夹前先把该文件夹下所有卡的 folder_id 置空
        // （这里通过 repository 完成，不让 ViewModel 处理）
        // 注意：当前实现保留「已删除文件夹但卡片还在未分类」的语义
        // 简单做法：直接删除文件夹，卡片将保留外键引用但查询过滤时显示为未分类
        folderDao.delete(folder)
    }

    suspend fun countCardsInFolder(folderId: Long): Int = folderDao.countCardsInFolder(folderId)
}
