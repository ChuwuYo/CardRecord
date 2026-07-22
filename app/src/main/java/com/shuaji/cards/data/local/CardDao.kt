package com.shuaji.cards.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    /**
     * 编辑页没有改动图片时，在同一数据库事务内合并最新图片引用。
     * 这会与后台旧 URI 迁移串行，避免“先读后写”把刚迁移的私有资产覆盖回旧值。
     */
    @Transaction
    suspend fun updatePreservingUserImage(card: CardEntity): Int {
        val stored = getById(card.id) ?: return 0
        return update(
            card.copy(
                imageUri = stored.imageUri,
                imageAssetId = stored.imageAssetId,
            ),
        )
    }

    @Delete
    suspend fun delete(card: CardEntity): Int

    /**
     * 旧外部 URI 迁移采用条件更新，避免后台复制期间覆盖用户刚完成的编辑。
     * 只有目标卡仍持有同一旧 URI 且尚无私有资产时才接纳结果。
     */
    @Query(
        "UPDATE cards SET image_asset_id = :imageAssetId, image_uri = NULL " +
            "WHERE id = :cardId AND image_asset_id IS NULL AND image_uri = :legacyUri",
    )
    suspend fun adoptOwnedImage(
        cardId: Long,
        legacyUri: String,
        imageAssetId: String,
    ): Int

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
