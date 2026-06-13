package com.example.creditcardtracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 卡片实体。
 *
 * - [requiredCount] 是年免年费所需消费笔数
 * - [currentCount] 当前已完成笔数
 * - [validUntilMillis] 卡片有效截止日
 * - [nextDueDateMillis] 下次年费结算日
 * - [colorArgb] 卡片主题色
 *
 * 卡面图片来源：
 * - [imageSourceType] = "NONE"     → 不显示图片（纯色卡）
 * - [imageSourceType] = "PROVIDER" → 使用预设卡组织（imageProviderKey 存枚举 key）
 * - [imageSourceType] = "USER"     → 使用用户自定义图片（imageUri 存相册 URI）
 *
 * 朝向：
 * - [cardOrientation] = "LANDSCAPE"（横版 1.586:1，标准卡片） / "PORTRAIT"（竖版）
 */
@Entity(tableName = "credit_cards")
data class CreditCardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val bank: String,
    @ColumnInfo(name = "card_number_masked")
    val cardNumberMasked: String,
    @ColumnInfo(name = "valid_until_millis")
    val validUntilMillis: Long? = null,
    @ColumnInfo(name = "next_due_date_millis")
    val nextDueDateMillis: Long? = null,
    @ColumnInfo(name = "required_count")
    val requiredCount: Int,
    @ColumnInfo(name = "current_count")
    val currentCount: Int = 0,
    @ColumnInfo(name = "cycle_start_millis")
    val cycleStartMillis: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "color_argb")
    val colorArgb: Int,
    @ColumnInfo(name = "note")
    val note: String = "",
    @ColumnInfo(name = "image_uri")
    val imageUri: String? = null,
    @ColumnInfo(name = "image_source_type", defaultValue = "USER")
    val imageSourceType: String = ImageSourceType.USER.name,
    @ColumnInfo(name = "image_provider_key")
    val imageProviderKey: String? = null,
    @ColumnInfo(name = "card_orientation", defaultValue = "LANDSCAPE")
    val cardOrientation: String = CardOrientation.LANDSCAPE.name,
    @ColumnInfo(name = "folder_id")
    val folderId: Long? = null,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "archived")
    val archived: Boolean = false,
)

enum class ImageSourceType { NONE, PROVIDER, USER }

enum class CardOrientation { LANDSCAPE, PORTRAIT }
