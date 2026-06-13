package com.example.creditcardtracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单笔消费事件，作为审计与撤销依据。
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CreditCardEntity::class,
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
    @ColumnInfo(name = "amount_cents")
    val amountCents: Long? = null,
    val merchant: String = "",
    @ColumnInfo(name = "occurred_at_millis")
    val occurredAtMillis: Long = System.currentTimeMillis(),
    val note: String = "",
)
