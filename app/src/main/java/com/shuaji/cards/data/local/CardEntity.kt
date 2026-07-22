package com.shuaji.cards.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shuaji.cards.data.DateToken
import java.time.Instant
import java.time.ZoneId

internal const val IMAGE_SOURCE_USER_KEY = "USER"
internal const val CARD_ORIENTATION_LANDSCAPE_KEY = "LANDSCAPE"

/**
 * 卡片实体。
 *
 * - [requiredCount] 是年免年费所需消费笔数
 * - [validUntilMillis] 卡片有效截止日（设置了就该有「已过期」提示）
 * - [nextDueDateMillis] 下次年费结算日
 * - [colorArgb] 卡片主题色
 * - [cardType] 借记卡 / 信用卡 / 未选择的稳定 key；旧卡保持未选择
 * - [statementDay] / [repaymentDay] 仅信用卡使用，都是每月 1..31 的日号
 *
 * `currentCount` 不存表。Repository 同时观察卡片与全部流水，并按该卡当前年费统计窗口
 * 在 Kotlin 中派生有效笔数；未设置结算日的卡为兼容旧行为，仍统计全部流水。
 * 旧字段 `cycleStartMillis` 因没有读取路径已移除。
 *
 * 卡面表现与卡组织是两个独立维度：
 * - [imageSourceType] = "NONE"     → 纯色样式
 * - [imageSourceType] = "PROVIDER" → 卡组织水印预设样式
 * - [imageSourceType] = "USER"     → 用户自定义图片样式
 * - [imageAssetId] 是应用私有目录中不可变图片的稳定内容 ID
 * - [imageUri] 只保留升级前的外部 URI，供幂等迁移；新写入不得再依赖它
 * - [imageProviderKey] 独立、可空，在以上任意样式中保存卡组织枚举 key；空值表示未选择卡组织
 *   USER 样式仍保存该值供切换样式恢复，但卡面不叠加徽标或装饰
 *
 * 朝向：
 * - [cardOrientation] = "LANDSCAPE"（横版 1.586:1，标准卡片） / "PORTRAIT"（竖版）
 *
 * 备份协议通过独立的 schema DTO 映射，不让 Room 模型重构隐式改变当前文件协议。
 */
@Entity(
    tableName = "cards",
    // 删除文件夹只解除分组；folder_id 索引同时满足外键查询与 Room schema 不变量。
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
    val cardType: String = CARD_TYPE_UNSPECIFIED_KEY,
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
    val imageSourceType: String = IMAGE_SOURCE_USER_KEY,
    @ColumnInfo(name = "image_provider_key")
    val imageProviderKey: String? = null,
    @ColumnInfo(name = "card_orientation", defaultValue = CARD_ORIENTATION_LANDSCAPE_KEY)
    val cardOrientation: String = CARD_ORIENTATION_LANDSCAPE_KEY,
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

/**
 * 把数据库中的卡面来源安全解析成枚举。未知值按纯色处理，避免外部导入或未来版本
 * 写入的新枚举值让旧版应用崩溃。
 */
val CardEntity.imageSourceTypeEnum: ImageSourceType
    get() = ImageSourceType.fromKey(imageSourceType)

/**
 * CardEntity 的辅助属性：把数据库存的 [CardEntity.cardOrientation] (String)
 * 安全转成 [CardOrientation] enum。**所有读卡面朝向的 UI 代码都走这个**，
 * 不要在调用点重复解析持久化字符串。
 *
 * 历史数据里如果出现异常字符串（手动改过 db、外部导入），回退到 LANDSCAPE——
 * 因为 PORTRAIT 是后期加的字段，老数据不可能是 PORTRAIT，fallback 安全。
 */
val CardEntity.cardOrientationEnum: CardOrientation
    get() = CardOrientation.fromKey(cardOrientation)

/** 有效期是本地日历日期；用户所在时区进入次日后才算过期。 */
fun CardEntity.isExpiredAt(
    now: Instant,
    zoneId: ZoneId,
): Boolean = validUntilMillis?.let { DateToken.toLocalDate(it).isBefore(now.atZone(zoneId).toLocalDate()) } == true

/**
 * 卡片朝向（与卡面物理方向一致）。
 *
 * [aspectRatio] 始终表示宽 / 高；卡面比例集中在朝向模型中，预览、列表与详情共用同一语义。
 * ISO/IEC 7810 ID-1 横版比例约为 1.586:1；竖版取其倒数。
 */
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
