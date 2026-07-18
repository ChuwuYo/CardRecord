package com.shuaji.cards.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.shuaji.cards.data.AnnualFeeCycle
import kotlinx.coroutines.flow.Flow

/**
 * 卡片与流水在 Repository 中按当前年费周期派生出的只读模型。
 * 它不是 Room 查询投影；[currentCount] 只统计 [cycle] 接纳的流水，
 * [lastSwipeAtMillis] 则保留全部历史中的最近时间。
 */
data class CardWithCount(
    val card: CardEntity,
    val currentCount: Int,
    val lastSwipeAtMillis: Long?,
    val cycle: AnnualFeeCycle,
)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY created_at_millis DESC")
    fun observeAll(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getById(id: Long): CardEntity?

    /** 备份导出与事务内周期归一化用的一次性全量读取。 */
    @Query("SELECT * FROM cards")
    suspend fun listAll(): List<CardEntity>

    @Upsert
    suspend fun upsert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity): Int

    @Delete
    suspend fun delete(card: CardEntity): Int

    /**
     * 备份导入 REPLACE 用：清空 cards 表。
     * 配合外键 `ON DELETE CASCADE`，会自动级联清空 transactions 全部行。
     */
    @Query("DELETE FROM cards")
    suspend fun deleteAll()
}

/** 流水支持新增、观察、删除单笔和按统计窗口重置。 */
@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity): Long

    @Query("SELECT * FROM transactions")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("DELETE FROM transactions WHERE card_id = :cardId")
    suspend fun deleteAllForCard(cardId: Long)

    @Query(
        "DELETE FROM transactions WHERE card_id = :cardId " +
            "AND occurred_at_millis >= :start AND occurred_at_millis < :end",
    )
    suspend fun deleteForCardInRange(
        cardId: Long,
        start: Long,
        end: Long,
    )

    /** 备份导出用的一次性全量读取。 */
    @Query("SELECT * FROM transactions")
    suspend fun listAll(): List<TransactionEntity>

    /** 单笔删除：流水列表每行一个垃圾桶按钮只删除对应行。 */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
