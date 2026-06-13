package com.shuaji.cards.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE archived = 0 ORDER BY created_at_millis DESC")
    fun observeActive(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE id = :id")
    fun observeById(id: Long): Flow<CardEntity?>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getById(id: Long): CardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CardEntity): Long

    @Update
    suspend fun update(card: CardEntity)

    @Delete
    suspend fun delete(card: CardEntity)

    @Query("UPDATE cards SET current_count = :count WHERE id = :id")
    suspend fun setCurrentCount(
        id: Long,
        count: Int,
    )

    @Query("UPDATE cards SET archived = :archived WHERE id = :id")
    suspend fun setArchived(
        id: Long,
        archived: Boolean,
    )

    @Query("UPDATE cards SET cycle_start_millis = :start, current_count = 0 WHERE id = :id")
    suspend fun resetCycle(
        id: Long,
        start: Long,
    )
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE card_id = :cardId ORDER BY occurred_at_millis DESC")
    fun observeForCard(cardId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE card_id = :cardId")
    suspend fun countForCard(cardId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Delete
    suspend fun delete(transaction: TransactionEntity)
}
