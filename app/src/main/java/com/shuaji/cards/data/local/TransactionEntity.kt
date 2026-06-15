package com.shuaji.cards.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 单笔消费事件，**只记时间和所属卡**。
 *
 * 字段极简化的原因：
 * - 金额 / 商户 / 备注在付款 App 里更详细，手动记只会更粗；
 * - 「当前已刷 N 笔」直接从本表 `COUNT(*)` 算，cards 表不再冗余存 currentCount；
 * - 撤销/重置操作 = `DELETE FROM transactions WHERE card_id = ?`。
 *
 * 索引说明：保持 `Index("card_id")` 单列索引——一年最多几十条流水，
 * 排序走文件 sort 完全够用，避免引入 Room 复合索引在 migration 阶段
 * 出现「索引名相同但列不同」的 schema 对不上陷阱。
 *
 * `@Serializable` 跟 `@Entity` 互不干扰——同一类型既存在数据库表里，
 * 也作为 `BackupBundle.transactions` 的元素直接走 JSON 序列化导出。
 */
@Serializable
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["id"],
            childColumns = ["card_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("card_id")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "card_id")
    val cardId: Long,
    @ColumnInfo(name = "occurred_at_millis")
    val occurredAtMillis: Long = System.currentTimeMillis(),
)
