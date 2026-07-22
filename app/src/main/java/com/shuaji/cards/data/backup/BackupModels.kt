package com.shuaji.cards.data.backup

import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.data.local.cardTypeEnum
import com.shuaji.cards.data.local.withNormalizedCreditDetails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 当前备份清单协议。
 *
 * 顶层必须显式包含 `version`、`cards`、`folders`、`transactions`。表内字段由独立 DTO
 * 固定，不直接序列化 Room Entity，避免数据库模型的默认值或重构悄悄改变文件协议。
 * 正式导出目录始终包含 `cardrecord_backup.json`；仅当卡片实际引用图片资源时创建
 * `card_images/`，JSON 通过资源 ID 对应图片文件。
 * 当前版本只接受 schema 3 目录，避免未发布协议的兼容分支成为长期维护负担。
 */
@Serializable
data class BackupBundle(
    @SerialName("version")
    val version: Int,
    @SerialName("cards")
    val cards: List<BackupCard>,
    @SerialName("folders")
    val folders: List<BackupFolder>,
    @SerialName("transactions")
    val transactions: List<BackupTransaction>,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 3
    }
}

/** 卡片备份记录；所有字段均属于当前协议，nullable 不等于可从 JSON 中省略。 */
@Serializable
data class BackupCard(
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
    @SerialName("imageAssetId")
    val imageAssetId: String?,
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
    @SerialName("cardType")
    val cardType: String,
    @SerialName("statementDay")
    val statementDay: Int?,
    @SerialName("repaymentDay")
    val repaymentDay: Int?,
)

@Serializable
data class BackupFolder(
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

@Serializable
data class BackupTransaction(
    @SerialName("id")
    val id: Long,
    @SerialName("cardId")
    val cardId: Long,
    @SerialName("occurredAtMillis")
    val occurredAtMillis: Long,
)

internal fun CardEntity.toBackup(): BackupCard {
    val normalized = withNormalizedCreditDetails()
    return BackupCard(
        id = id,
        name = name,
        bank = bank,
        cardNumberMasked = cardNumberMasked,
        validUntilMillis = validUntilMillis,
        nextDueDateMillis = nextDueDateMillis,
        requiredCount = requiredCount,
        colorArgb = colorArgb,
        note = note,
        imageAssetId = imageAssetId,
        imageSourceType = imageSourceType,
        imageProviderKey = imageProviderKey,
        cardOrientation = cardOrientation,
        folderId = folderId,
        createdAtMillis = createdAtMillis,
        cardType = normalized.cardTypeEnum.key,
        statementDay = normalized.statementDay,
        repaymentDay = normalized.repaymentDay,
    )
}

internal fun BackupCard.toEntity(): CardEntity {
    val resolvedType = checkNotNull(CardType.fromKeyOrNull(cardType))
    return CardEntity(
        id = id,
        name = name,
        bank = bank,
        cardNumberMasked = cardNumberMasked,
        cardType = resolvedType.key,
        statementDay = statementDay.takeIf { resolvedType == CardType.CREDIT },
        repaymentDay = repaymentDay.takeIf { resolvedType == CardType.CREDIT },
        validUntilMillis = validUntilMillis,
        nextDueDateMillis = nextDueDateMillis,
        requiredCount = requiredCount,
        colorArgb = colorArgb,
        note = note,
        // 设备专属 URI 不属于备份协议；私有资产已经随目录恢复。
        imageUri = null,
        imageAssetId = imageAssetId,
        imageSourceType = imageSourceType,
        imageProviderKey = imageProviderKey,
        cardOrientation = cardOrientation,
        folderId = folderId,
        createdAtMillis = createdAtMillis,
    )
}

internal fun CardFolderEntity.toBackup(): BackupFolder =
    BackupFolder(
        id = id,
        name = name,
        colorArgb = colorArgb,
        sortOrder = sortOrder,
        createdAtMillis = createdAtMillis,
    )

internal fun BackupFolder.toEntity(): CardFolderEntity =
    CardFolderEntity(
        id = id,
        name = name,
        colorArgb = colorArgb,
        sortOrder = sortOrder,
        createdAtMillis = createdAtMillis,
    )

internal fun TransactionEntity.toBackup(): BackupTransaction =
    BackupTransaction(
        id = id,
        cardId = cardId,
        occurredAtMillis = occurredAtMillis,
    )

internal fun BackupTransaction.toEntity(): TransactionEntity =
    TransactionEntity(
        id = id,
        cardId = cardId,
        occurredAtMillis = occurredAtMillis,
    )

/** 备份确认页使用的可信摘要；只由完整解码并校验过的 [BackupBundle] 生成。 */
data class BackupPreview(
    val cardCount: Int,
    val folderCount: Int,
    val transactionCount: Int,
    val lastModifiedMillis: Long?,
    /** 预览时所读 JSON 清单的 SHA-256，用于确认后阻止导入已被替换的内容。 */
    val manifestSha256: String,
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
)

class BackupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
