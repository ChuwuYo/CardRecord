package com.shuaji.cards.data.local

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
@Entity(tableName = "cards")
data class CardEntity(
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

/**
 * CardEntity 的辅助属性：把数据库存的 [CardEntity.cardOrientation] (String)
 * 安全转成 [CardOrientation] enum。**所有读卡面朝向的 UI 代码都走这个**，
 * 不要在调用点再写 `runCatching { CardOrientation.valueOf(...) }.getOrDefault(...)`。
 *
 * 历史数据里如果出现异常字符串（手动改过 db、外部导入），回退到 LANDSCAPE——
 * 因为 PORTRAIT 是后期加的字段，老数据不可能是 PORTRAIT，fallback 安全。
 */
val CardEntity.cardOrientationEnum: CardOrientation
    get() = runCatching { CardOrientation.valueOf(cardOrientation) }.getOrDefault(CardOrientation.LANDSCAPE)

/**
 * 卡片朝向（与卡面物理方向一致）。
 *
 * **aspectRatio 走这里、不走组件里**——把"标准卡比例"作为领域知识挂在
 * 数据模型上，UI 组件只是消费方。这样做的好处：
 * 1) 任何新加的预览/列表/详情渲染位置，都从 `cardOrientation.aspectRatio`
 *    读，不会再写出一份 1.586f 散落各处；
 * 2) 哪天接 Apple Wallet、Google Wallet 等比例不同的卡规格，
 *    改一处即可（甚至可以按 orientation 套不同比例）；
 * 3) 测试可以断言 enum 上的比例而不是去测量像素。
 *
 * ISO/IEC 7810 ID-1 标准卡（CR80）：85.60 mm × 53.98 mm ⇒ 1.586:1。
 * PORTRAIT 旋转 90°，宽高比变 1:1.586 = 0.631。
 */
enum class CardOrientation(
    val aspectRatio: Float,
) {
    LANDSCAPE(1.586f),
    PORTRAIT(0.631f),
}
