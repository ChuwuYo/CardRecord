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

    @Query("SELECT * FROM cards")
    suspend fun listAll(): List<CardEntity>

    @Upsert
    suspend fun upsert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity): Int

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

    @Query(
        "UPDATE cards SET image_asset_id = :imageAssetId, image_uri = NULL " +
            "WHERE id = :cardId AND image_asset_id IS NULL AND image_uri = :legacyUri",
    )
    suspend fun adoptOwnedImage(
        cardId: Long,
        legacyUri: String,
        imageAssetId: String,
    ): Int

    @Query("DELETE FROM cards")
    suspend fun deleteAll()
}

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

    @Query("SELECT * FROM transactions")
    suspend fun listAll(): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
