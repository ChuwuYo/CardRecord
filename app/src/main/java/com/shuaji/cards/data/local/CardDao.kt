package com.shuaji.cards.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
 * Repository / UI 拿到的就是 [CardWithCount]，永远新鲜、不可能漂移。
 *
 * 命名上 `current_count` / `last_swipe_at_millis` 是 SQL 列名，跟旧字段同名方便阅读，
 * 含义从「冗余计数」变成「由 SQL 实时算出的派生值」。
 *
 * [lastSwipeAtMillis] 是为了给流水表的 `occurred_at_millis` 一个 UI 消费路径
 * —— 详情页"最近一笔时间"显示，遵循「凡是存在的字段都要有 UI 消费路径」原则。
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity)

    @Delete
    suspend fun delete(card: CardEntity)
}

/**
 * 流水表只做两件事：插一笔、删该卡全部。
 * 「撤销最后一笔」/「重置年度笔数」都是 `DELETE FROM transactions WHERE card_id = ?`。
 * 「当前笔数」从 COUNT 算，「最近一笔时间」从 MAX 算——流水表不再承担计数职责。
 *
 * 流水表瘦到 2 字段（card_id, occurred_at_millis）后，按时间倒序把全部
 * 时间戳暴露给详情页「流水列表」section——这才是流水行真正的归宿。
 * 凡是存在的行就要在 UI 里有消费，否则就是写而不读的脏数据。
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
}
