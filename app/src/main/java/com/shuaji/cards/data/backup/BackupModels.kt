package com.shuaji.cards.data.backup

import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 备份文件 schema 1。
 *
 * 顶层必须显式包含 `version`、`cards`、`folders`、`transactions`。表内字段由独立的 V1 DTO
 * 固定，不直接序列化 Room Entity，避免数据库模型的默认值或重构悄悄改变已经发布的文件协议。
 * 破坏性格式变更必须提升 [version] 并在导入边界显式迁移。
 *
 * `imageUri` 是设备相关的 `content://` 引用。仍按 schema 1 原样导出以支持同设备恢复；
 * UI 通过 [ImportResult.imageUriUserCount] 提醒用户跨设备后可能需要重新选择图片。
 */
@Serializable
data class BackupBundle(
    @Required
    @SerialName("version")
    val version: Int = SCHEMA_VERSION,
    @Required
    @SerialName("cards")
    val cards: List<BackupCardV1> = emptyList(),
    @Required
    @SerialName("folders")
    val folders: List<BackupFolderV1> = emptyList(),
    @Required
    @SerialName("transactions")
    val transactions: List<BackupTransactionV1> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/** schema 1 的卡片记录。所有字段都是已发布协议中的必填字段，包括可空字段。 */
@Serializable
data class BackupCardV1(
    @SerialName("id")
    val id: Long,
    @SerialName("name")
    val name: String,
    @SerialName("bank")
    val bank: String,
    @SerialName("cardNumberMasked")
    val cardNumberMasked: String,
    @SerialName("validUntilMillis")
    val validUntilMillis: Long?,
    @SerialName("nextDueDateMillis")
    val nextDueDateMillis: Long?,
    @SerialName("requiredCount")
    val requiredCount: Int,
    @SerialName("colorArgb")
    val colorArgb: Int,
    @SerialName("note")
    val note: String,
    @SerialName("imageUri")
    val imageUri: String?,
    @SerialName("imageSourceType")
    val imageSourceType: String,
    @SerialName("imageProviderKey")
    val imageProviderKey: String?,
    @SerialName("cardOrientation")
    val cardOrientation: String,
    @SerialName("folderId")
    val folderId: Long?,
    @SerialName("createdAtMillis")
    val createdAtMillis: Long,
)

/** schema 1 的文件夹记录。 */
@Serializable
data class BackupFolderV1(
    @SerialName("id")
    val id: Long,
    @SerialName("name")
    val name: String,
    @SerialName("colorArgb")
    val colorArgb: Int,
    @SerialName("sortOrder")
    val sortOrder: Int,
    @SerialName("createdAtMillis")
    val createdAtMillis: Long,
)

/** schema 1 的消费记录。 */
@Serializable
data class BackupTransactionV1(
    @SerialName("id")
    val id: Long,
    @SerialName("cardId")
    val cardId: Long,
    @SerialName("occurredAtMillis")
    val occurredAtMillis: Long,
)

internal fun CardEntity.toBackupV1(): BackupCardV1 =
    BackupCardV1(
        id = id,
        name = name,
        bank = bank,
        cardNumberMasked = cardNumberMasked,
        validUntilMillis = validUntilMillis,
        nextDueDateMillis = nextDueDateMillis,
        requiredCount = requiredCount,
        colorArgb = colorArgb,
        note = note,
        imageUri = imageUri,
        imageSourceType = imageSourceType,
        imageProviderKey = imageProviderKey,
        cardOrientation = cardOrientation,
        folderId = folderId,
        createdAtMillis = createdAtMillis,
    )

internal fun BackupCardV1.toEntity(): CardEntity =
    CardEntity(
        id = id,
        name = name,
        bank = bank,
        cardNumberMasked = cardNumberMasked,
        validUntilMillis = validUntilMillis,
        nextDueDateMillis = nextDueDateMillis,
        requiredCount = requiredCount,
        colorArgb = colorArgb,
        note = note,
        imageUri = imageUri,
        imageSourceType = imageSourceType,
        imageProviderKey = imageProviderKey,
        cardOrientation = cardOrientation,
        folderId = folderId,
        createdAtMillis = createdAtMillis,
    )

internal fun CardFolderEntity.toBackupV1(): BackupFolderV1 =
    BackupFolderV1(
        id = id,
        name = name,
        colorArgb = colorArgb,
        sortOrder = sortOrder,
        createdAtMillis = createdAtMillis,
    )

internal fun BackupFolderV1.toEntity(): CardFolderEntity =
    CardFolderEntity(
        id = id,
        name = name,
        colorArgb = colorArgb,
        sortOrder = sortOrder,
        createdAtMillis = createdAtMillis,
    )

internal fun TransactionEntity.toBackupV1(): BackupTransactionV1 =
    BackupTransactionV1(
        id = id,
        cardId = cardId,
        occurredAtMillis = occurredAtMillis,
    )

internal fun BackupTransactionV1.toEntity(): TransactionEntity =
    TransactionEntity(
        id = id,
        cardId = cardId,
        occurredAtMillis = occurredAtMillis,
    )

/** SAF 文件确认页使用的可信摘要；只由完整解码并校验过的 [BackupBundle] 生成。 */
data class BackupFileInfo(
    val cardCount: Int,
    val folderCount: Int,
    val transactionCount: Int,
    val imageUriUserCount: Int,
    val lastModifiedMillis: Long?,
    /** 预览时所读原始文件的 SHA-256，用于确认后阻止导入已被替换的内容。 */
    val contentSha256: String,
)

/**
 * 导入模式。导入前用户选一个：
 * - [REPLACE] 先清空数据库全部表，再写入备份
 * - [MERGE]   保留现有数据，把备份追加进去；card / folder / transaction 都会自增分配新 id
 */
enum class ImportMode { REPLACE, MERGE }

/** [BackupRepository.cancelActive] 的阶段化结果，避免把“无操作”和“已经开始提交”混成同一个布尔值。 */
enum class BackupCancelResult {
    CANCELLED,
    COMMIT_IN_PROGRESS,
    NO_ACTIVE_OPERATION,
}

/**
 * 导入结果。返回给 UI 用：
 * - [cardsAdded] / [foldersAdded] / [transactionsAdded] — 实际插入条数
 * - [transactionsSkipped] — MERGE 模式下因 cardId 找不到映射被跳过的孤立 transaction 数
 * - [cardsSkippedInvalidFolder] — REPLACE 模式下被置为未分组的 card 数（folder 引用失效）
 * - [duplicateFolderNames] / [duplicateCardNames] — MERGE 模式下与现库重名的 folder / card 数
 * - [imageUriUserCount] — 备份里 `imageSourceType == USER` 且确有图片 URI 的卡数；UI 据此提示用户
 *   自定义卡面可能需要重新选择，不等同于已确认 URI 失效
 * - 出错时抛 [BackupException]，UI 层 try-catch 转 Snackbar
 */
data class ImportResult(
    val cardsAdded: Int,
    val foldersAdded: Int,
    val transactionsAdded: Int,
    val transactionsSkipped: Int = 0,
    val cardsSkippedInvalidFolder: Int = 0,
    val duplicateFolderNames: Int = 0,
    val duplicateCardNames: Int = 0,
    val imageUriUserCount: Int = 0,
)

class BackupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
