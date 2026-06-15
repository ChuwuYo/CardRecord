package com.shuaji.cards.data

import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardWithCount
import com.shuaji.cards.data.local.TransactionDao
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 仓库层：**只暴露「业务用例」**，不暴露 Room Entity / DAO 给 ViewModel。
 *
 * 收口原则：
 * - ViewModel 拿到的都是 [CardWithCount]（自带 currentCount），不直接拿 CardEntity
 *   —— 这样 UI 不可能"漏算" currentCount，从源头杜绝 cards.currentCount 漂移的可能。
 * - 流水表的操作只有「记一笔」「重置」，没有「撤销最后一笔」「看历史流水列表」——
 *   详情页就是一个"当前笔数 + 重置按钮"，不需要流水列表 UI。
 * - 文件夹是辅助分组，接口与旧版基本保持一致。
 */
class CardRepository(
    private val cardDao: CardDao,
    private val transactionDao: TransactionDao,
    private val folderDao: CardFolderDao,
) {
    // ── 卡（带实时笔数） ──

    fun observeCards(): Flow<List<CardWithCount>> = cardDao.observeActiveWithCount()

    fun observeCard(id: Long): Flow<CardWithCount?> = cardDao.observeByIdWithCount(id)

    suspend fun upsertCard(card: CardEntity): Long = cardDao.upsert(card)

    suspend fun deleteCard(card: CardEntity) = cardDao.delete(card)

    /**
     * 记一笔消费：插一行流水。currentCount 由 SQL 实时算，无需 update。
     *
     * 返回新插入流水的 id（成功落库）或 null（卡不存在）。
     */
    suspend fun recordSwipe(cardId: Long): Long? {
        // 校验卡存在 —— 防止外键插入失败的 silent 错误。
        val card = cardDao.getById(cardId) ?: return null
        return transactionDao.insert(
            TransactionEntity(cardId = card.id, occurredAtMillis = System.currentTimeMillis()),
        )
    }

    /**
     * 重置年度笔数 = 删该卡所有流水。currentCount 由 SQL 重算为 0。
     */
    suspend fun resetCardCycle(cardId: Long) {
        transactionDao.deleteAllForCard(cardId)
    }

    /**
     * 详情页「流水列表」：按时间倒序拉该卡全部流水行。
     * 流水表瘦到 2 字段后，每行只有 (card_id, occurred_at_millis)，
     * UI 拿到的就是一个时间戳序列——按「刷一笔 = 一行」原则展示。
     */
    fun observeTransactions(cardId: Long): Flow<List<TransactionEntity>> =
        transactionDao.observeForCard(cardId)

    // ── 文件夹 ──

    fun observeFolders(): Flow<List<CardFolderEntity>> = folderDao.observeAll()

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
