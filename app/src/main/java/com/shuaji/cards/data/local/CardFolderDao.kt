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

data class FolderWithCardCount(
    @Embedded val folder: CardFolderEntity,
    @ColumnInfo(name = "card_count") val cardCount: Int,
)

@Dao
interface CardFolderDao {
    @Query(
        "SELECT f.*, COUNT(c.id) AS card_count FROM card_folders AS f " +
            "LEFT JOIN cards AS c ON c.folder_id = f.id " +
            "GROUP BY f.id ORDER BY f.sort_order ASC, f.created_at_millis ASC",
    )
    fun observeWithCardCounts(): Flow<List<FolderWithCardCount>>

    @Query("SELECT * FROM card_folders ORDER BY sort_order ASC, created_at_millis ASC")
    fun observeAll(): Flow<List<CardFolderEntity>>

    /**
     * 备份导出用：一次性读所有文件夹。
     */
    @Query("SELECT * FROM card_folders")
    suspend fun listAll(): List<CardFolderEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: CardFolderEntity): Long

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM card_folders")
    suspend fun nextSortOrder(): Int

    @Update
    suspend fun update(folder: CardFolderEntity): Int

    @Delete
    suspend fun delete(folder: CardFolderEntity): Int

    /**
     * 备份导入 REPLACE 用：清空 card_folders 表。
     */
    @Query("DELETE FROM card_folders")
    suspend fun deleteAll()
}
