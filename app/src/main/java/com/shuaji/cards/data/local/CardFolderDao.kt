package com.shuaji.cards.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CardFolderDao {
    @Query("SELECT * FROM card_folders ORDER BY sort_order ASC, created_at_millis ASC")
    fun observeAll(): Flow<List<CardFolderEntity>>

    @Query("SELECT * FROM card_folders WHERE id = :id")
    suspend fun getById(id: Long): CardFolderEntity?

    @Query("SELECT COUNT(*) FROM cards WHERE folder_id = :folderId")
    suspend fun countCardsInFolder(folderId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: CardFolderEntity): Long

    @Update
    suspend fun update(folder: CardFolderEntity)

    @Delete
    suspend fun delete(folder: CardFolderEntity)
}
