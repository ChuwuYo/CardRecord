package com.shuaji.cards.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 卡片 + 实时计算笔数的视图模型。
 *
 * 旧实现把 `currentCount` 存进 cards 表，每次 recordTransaction 都要
 * `UPDATE cards SET current_count = ?`，存在以下问题：
 * - 双源一致性：流水和 currentCount 任何一个被错改就会漂移
 * - 删除最后一条流水后还要回滚 currentCount
 *
 * 新实现：cards 表只存静态属性，currentCount 从 transactions 表
 * `COUNT(*)` 算（LEFT JOIN + GROUP BY 子查询一次拿完）。
 * Repository / UI 只读取查询时派生的 [CardWithCount]，避免计数与流水形成两个写入源。
 *
 * 命名上 `current_count` / `last_swipe_at_millis` 是 SQL 列名，跟旧字段同名方便阅读，
 * 含义从「冗余计数」变成「由 SQL 实时算出的派生值」。
 *
 * [lastSwipeAtMillis] 由流水时间的 `MAX` 派生，供详情页显示最近一笔时间。
 */
data class CardWithCount(
    @Embedded
    val card: CardEntity,
    @ColumnInfo(name = "current_count")
    val currentCount: Int,
    @ColumnInfo(name = "last_swipe_at_millis")
    val lastSwipeAtMillis: Long?,
)

@Dao
interface CardDao {
    /**
     * 主页用：拿所有卡 + 实时计算的 currentCount + 最近一笔时间。
     * 一次 SQL，LEFT JOIN + GROUP BY 子查询——N 张卡一次往返。
     */
    @Query(
        """
        SELECT c.*, COALESCE(t.cnt, 0) AS current_count, t.last_at AS last_swipe_at_millis
        FROM cards c
        LEFT JOIN (
            SELECT card_id, COUNT(*) AS cnt, MAX(occurred_at_millis) AS last_at
            FROM transactions
            GROUP BY card_id
        ) t ON t.card_id = c.id
        ORDER BY c.created_at_millis DESC
        """,
    )
    fun observeActiveWithCount(): Flow<List<CardWithCount>>

    /**
     * 详情页用：拿单张卡 + 实时计算的 currentCount + 最近一笔时间。
     * 同样走 LEFT JOIN，0 笔时返回 0 / NULL。
     */
    @Query(
        """
        SELECT c.*, COALESCE(t.cnt, 0) AS current_count, t.last_at AS last_swipe_at_millis
        FROM cards c
        LEFT JOIN (
            SELECT card_id, COUNT(*) AS cnt, MAX(occurred_at_millis) AS last_at
            FROM transactions
            GROUP BY card_id
        ) t ON t.card_id = c.id
        WHERE c.id = :id
        """,
    )
    fun observeByIdWithCount(id: Long): Flow<CardWithCount?>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getById(id: Long): CardEntity?

    /**
     * 备份导出用：一次性读所有卡（不 observe）。
     */
    @Query("SELECT * FROM cards")
    suspend fun listAll(): List<CardEntity>

    /**
     * 自动续期用：找出所有 nextDueDateMillis < now 的卡。
     * 这些卡应当在新周期开始时重置笔数（删流水）+ 把 nextDueDate 推到下一年。
     * 一次性 while 循环推 N 年，避免下次启动又触发。
     */
    @Query("SELECT * FROM cards WHERE next_due_date_millis IS NOT NULL AND next_due_date_millis < :now")
    suspend fun findOverdue(now: Long): List<CardEntity>

    @Upsert
    suspend fun upsert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity)

    @Delete
    suspend fun delete(card: CardEntity)

    /**
     * 备份导入 REPLACE 用：清空 cards 表。
     * 配合外键 `ON DELETE CASCADE`，会自动级联清空 transactions 全部行。
     */
    @Query("DELETE FROM cards")
    suspend fun deleteAll()
}

/**
 * 流水支持新增、按卡查询、删除单笔和清空某卡周期。
 * 「当前笔数」从 COUNT 派生，「最近一笔时间」从 MAX 派生；详情页按时间倒序展示流水。
 */
@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Query("DELETE FROM transactions WHERE card_id = :cardId")
    suspend fun deleteAllForCard(cardId: Long)

    /**
     * 详情页「流水列表」用：按时间倒序拉该卡全部流水。
     * 顺序固定为 DESC——最新一笔在最上面，符合用户直觉。
     */
    @Query("SELECT * FROM transactions WHERE card_id = :cardId ORDER BY occurred_at_millis DESC")
    fun observeForCard(cardId: Long): Flow<List<TransactionEntity>>

    /**
     * 备份导出用：一次性读所有流水。
     */
    @Query("SELECT * FROM transactions")
    suspend fun listAll(): List<TransactionEntity>

    /**
     * 单笔删除：流水列表每行一个垃圾桶按钮 → 删这一行。
     * 一次删一行，不是"重置一把全清"。
     */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
