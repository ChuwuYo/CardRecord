package com.example.creditcardtracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 卡文件夹：用于对卡片进行分组（例如"商旅"、"日常"、"积分"等）。
 *
 * - [colorArgb] 文件夹在主页中作为分组色块显示
 * - [iconKey] 与 [CardNetworkProvider] 类似的 key，用于动态取图标资源
 * - [sortOrder] 主页分组排序：升序排列，值小者排在前
 */
@Entity(tableName = "card_folders")
data class CardFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "color_argb")
    val colorArgb: Int,
    @ColumnInfo(name = "icon_key")
    val iconKey: String = "folder",
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = System.currentTimeMillis(),
)
