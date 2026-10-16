package com.shuaji.cards.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shuaji.cards.data.DateToken
import java.time.Instant
import java.time.ZoneId

private const val IMAGE_SOURCE_USER_KEY = "USER"
private const val CARD_ORIENTATION_LANDSCAPE_KEY = "LANDSCAPE"

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = CardFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("folder_id")],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val bank: String,
    @ColumnInfo(name = "card_number_masked")
    val cardNumberMasked: String,
    @ColumnInfo(name = "card_type", defaultValue = CARD_TYPE_UNSPECIFIED_KEY)
    val cardType: CardType = CardType.UNSPECIFIED,
    @ColumnInfo(name = "statement_day")
    val statementDay: Int? = null,
    @ColumnInfo(name = "repayment_day")
    val repaymentDay: Int? = null,
    @ColumnInfo(name = "valid_until_millis")
    val validUntilMillis: Long? = null,
    @ColumnInfo(name = "next_due_date_millis")
    val nextDueDateMillis: Long? = null,
    @ColumnInfo(name = "required_count")
    val requiredCount: Int,
    @ColumnInfo(name = "color_argb")
    val colorArgb: Int,
    @ColumnInfo(name = "note")
    val note: String = "",
    @ColumnInfo(name = "image_uri")
    val imageUri: String? = null,
    @ColumnInfo(name = "image_asset_id")
    val imageAssetId: String? = null,
    @ColumnInfo(name = "image_source_type", defaultValue = IMAGE_SOURCE_USER_KEY)
    val imageSourceType: ImageSourceType = ImageSourceType.USER,
    @ColumnInfo(name = "image_provider_key")
    val imageProviderKey: String? = null,
    @ColumnInfo(name = "card_orientation", defaultValue = CARD_ORIENTATION_LANDSCAPE_KEY)
    val cardOrientation: CardOrientation = CardOrientation.LANDSCAPE,
    @ColumnInfo(name = "folder_id")
    val folderId: Long? = null,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = System.currentTimeMillis(),
)

enum class ImageSourceType(
    val key: String,
) {
    NONE("NONE"),
    PROVIDER("PROVIDER"),
    USER(IMAGE_SOURCE_USER_KEY),
    ;

    companion object {
        fun fromKey(key: String): ImageSourceType = entries.firstOrNull { it.key == key } ?: NONE
    }
}

fun CardEntity.isExpiredAt(
    now: Instant,
    zoneId: ZoneId,
): Boolean = validUntilMillis?.let { DateToken.toLocalDate(it).isBefore(now.atZone(zoneId).toLocalDate()) } == true

enum class CardOrientation(
    val key: String,
    val aspectRatio: Float,
) {
    LANDSCAPE(CARD_ORIENTATION_LANDSCAPE_KEY, 1.586f),
    PORTRAIT("PORTRAIT", 0.631f),
    ;

    companion object {
        fun fromKey(key: String): CardOrientation = entries.firstOrNull { it.key == key } ?: LANDSCAPE
    }
}
