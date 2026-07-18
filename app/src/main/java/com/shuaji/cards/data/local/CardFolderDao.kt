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

    /**
     * 备份导出用：一次性读所有文件夹。
     */
    @Query("SELECT * FROM card_folders")
    suspend fun listAll(): List<CardFolderEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: CardFolderEntity): Long

    @Update
    suspend fun update(folder: CardFolderEntity)

    @Delete
    suspend fun delete(folder: CardFolderEntity)

    /**
     * 备份导入 REPLACE 用：清空 card_folders 表。
     */
    @Query("DELETE FROM card_folders")
    suspend fun deleteAll()
}
