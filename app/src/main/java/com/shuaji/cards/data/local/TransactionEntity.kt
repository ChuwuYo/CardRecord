package com.shuaji.cards.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单笔消费事件，**只记时间和所属卡**。
 *
 * 字段极简化的原因：
 * - 金额 / 商户 / 备注在付款 App 里更详细，手动记只会更粗；
 * - cards 表不冗余存 currentCount；Repository 按当前年费统计窗口筛选本表流水后派生笔数；
 * - 手动重置已排期卡片时只删除当前窗口内流水，历史窗口流水继续保留；未排期卡片兼容旧行为，
 *   重置会删除该卡全部流水。
 *
 * 索引说明：保持 `Index("card_id")` 单列索引——一年最多几十条流水，
 * 排序走文件 sort 完全够用，避免引入 Room 复合索引在 migration 阶段
 * 出现「索引名相同但列不同」的 schema 对不上陷阱。
 *
 * 备份协议通过独立的 schema DTO 映射，不让 Room 模型的重构隐式改变已发布的 JSON。
 */
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
