package com.shuaji.cards.data

import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardWithCount
import com.shuaji.cards.data.local.TransactionDao
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

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
    fun observeTransactions(cardId: Long): Flow<List<TransactionEntity>> = transactionDao.observeForCard(cardId)

    /**
     * 单笔删除：流水列表里每行垃圾桶按钮触发。
     * 删完 SQL COUNT 重算 currentCount，UI 立刻刷新。
     */
    suspend fun deleteTransaction(id: Long) {
        transactionDao.deleteById(id)
    }

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

    // ── 自动续期（App 启动时跑） ──

    /**
     * **凡是填了 `nextDueDateMillis` 的卡，在到这天时都要自动续期**——
     * 不然这个字段就是「写而不读」的纯死字段。
     *
     * 行为：
     * 1. 找出所有 `nextDueDateMillis < now` 的卡（这些卡是上一周期到期后没重置的）
     * 2. 对每张卡：
     *    - 删该卡所有流水（`currentCount` 由 SQL COUNT 重算为 0）
     *    - 把 `nextDueDateMillis` 推到 `> now`（while 循环：可能累积 N 年没开 app）
     * 3. 返回续期卡数（0 = 这次没卡需要续期，调用方不弹 Snackbar）
     *
     * 用 [Calendar.add(YEAR, 1)] 而不是 +365 天——自动处理闰年 2-29。
     */
    suspend fun resetOverdueCycles(now: Long): Int {
        val overdue = cardDao.findOverdue(now)
        if (overdue.isEmpty()) return 0
        overdue.forEach { card ->
            val currentNext = card.nextDueDateMillis ?: return@forEach
            // 一次性推 N 年：用户半年没开 app，nextDueDate 可能已经过 1+ 年
            var next = currentNext
            while (next <= now) {
                val cal = GregorianCalendar()
                cal.time = Date(next)
                cal.add(Calendar.YEAR, 1)
                next = cal.timeInMillis
            }
            // 删流水 + 推 nextDueDate
            transactionDao.deleteAllForCard(card.id)
            cardDao.update(card.copy(nextDueDateMillis = next))
        }
        return overdue.size
    }
}
