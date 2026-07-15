package com.shuaji.cards.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 卡片实体。
 *
 * - [requiredCount] 是年免年费所需消费笔数
 * - [validUntilMillis] 卡片有效截止日（设置了就该有「已过期」提示）
 * - [nextDueDateMillis] 下次年费结算日
 * - [colorArgb] 卡片主题色
 *
 * `currentCount` 不存表，由 transactions 的 `COUNT(*)` 派生；
 * 旧字段 `cycleStartMillis` 因没有读取路径已移除。
 *
 * 卡面图片来源：
 * - [imageSourceType] = "NONE"     → 不显示图片（纯色卡）
 * - [imageSourceType] = "PROVIDER" → 使用预设卡组织（imageProviderKey 存枚举 key）
 * - [imageSourceType] = "USER"     → 使用用户自定义图片（imageUri 存相册 URI）
 *
 * 朝向：
 * - [cardOrientation] = "LANDSCAPE"（横版 1.586:1，标准卡片） / "PORTRAIT"（竖版）
 *
 * `@Serializable` 跟 `@Entity` 互不干扰——Room 用 kapt 生成 DAO，kotlinx-serialization
 * 用 compiler plugin 生成 encoder / decoder，两条独立通路。这个 Entity 既存在数据库表里，
 * 也作为 `BackupBundle.cards` 的元素直接走 JSON 序列化导出。
 */
@Serializable
@Entity(
    tableName = "cards",
    // folder_id 外键：删除文件夹时把该文件夹下卡片的 folder_id 置空（卡片归「未分类」）。
    // 历史坑：MIGRATION_5_6 早就在建表 SQL 里写了这条 ON DELETE SET NULL 外键，但实体一直没
    // 声明，导致 (1) 全新安装的 cards 表其实没外键、SET NULL 不生效；(2) v5→v6 升级用户的 DB
    // 带外键、与实体期望不一致、Room 启动校验崩溃。MIGRATION_6_7 统一两条路径，使实体声明
    // 与磁盘 schema 一致。indices 必须同时声明：Room 期望被外键引用的子列上有 index_cards_folder_id。
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
