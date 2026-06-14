package com.shuaji.cards.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 卡文件夹：用于对卡片进行分组（例如"商旅"、"日常"、"积分"等）。
 *
 * - [colorArgb] 文件夹在主页中作为分组色块显示
 * - [sortOrder] 主页分组排序：升序排列，值小者排在前
 *
 * 字段精简：删了 `iconKey` —— 历史实现里它存的字符串从没有被任何 UI
 * 消费过（主页文件夹只显示色块 + 名字），属于「写而不读」死字段。
 */
@Entity(tableName = "card_folders")
data class CardFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "color_argb")
    val colorArgb: Int,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = System.currentTimeMillis(),
)
