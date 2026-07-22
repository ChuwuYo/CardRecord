package com.shuaji.cards.data.backup

import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.data.local.cardTypeEnum
import com.shuaji.cards.data.local.withNormalizedCreditDetails
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 备份文件当前 schema。
 *
 * 顶层必须显式包含 `version`、`cards`、`folders`、`transactions`。表内字段由独立 DTO
 * 固定，不直接序列化 Room Entity，避免数据库模型的默认值或重构悄悄改变已经发布的文件协议。
 * schema 2 在卡片记录中新增卡类型、账单日和还款日；schema 3 新增应用私有图片资源 ID。
 * 正式导出目录始终包含 `cardrecord_backup.json`；仅当卡片实际引用图片资源时创建
 * `card_images/`，JSON 通过资源 ID 对应图片文件。
 * 导入仍兼容冻结的 schema 1/2 单 JSON 文件。
 * 旧 `imageUri` 仅用于同设备幂等迁移，跨设备提示仍由 [ImportResult.legacyImageUriCount] 承担。
 */
@Serializable
data class BackupBundle(
    @Required
    @SerialName("version")
    val version: Int = SCHEMA_VERSION,
    @Required
    @SerialName("cards")
    val cards: List<BackupCard> = emptyList(),
    @Required
    @SerialName("folders")
    val folders: List<BackupFolderV1> = emptyList(),
    @Required
    @SerialName("transactions")
    val transactions: List<BackupTransactionV1> = emptyList(),
) {
    companion object {
        const val MIN_SUPPORTED_SCHEMA_VERSION: Int = 1
        const val SCHEMA_VERSION: Int = 3
    }
}

/**
 * 卡片备份记录。
 *
 * schema 1 已发布字段继续保持必填；schema 2 的三个新增字段带解码默认值，只为让旧文件可读取。
 * schema 2 文件仍由结构校验要求显式提供 [cardType]，不能靠该默认值掩盖损坏文件。
 */
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
    @SerialName("imageUri")
    val imageUri: String?,
    @SerialName("imageAssetId")
    val imageAssetId: String? = null,
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
    val cardType: String? = null,
    @SerialName("statementDay")
    val statementDay: Int? = null,
    @SerialName("repaymentDay")
    val repaymentDay: Int? = null,
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
        // 已有可移植私有资产时不再把设备专属 content:// URI 写入备份。
        imageUri = imageUri?.takeIf { it.isNotBlank() && imageAssetId.isNullOrBlank() },
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

internal fun BackupCard.toEntity(schemaVersion: Int): CardEntity {
    val resolvedType =
        if (schemaVersion == BackupBundle.MIN_SUPPORTED_SCHEMA_VERSION) {
            CardType.UNSPECIFIED
        } else {
            checkNotNull(cardType?.let(CardType::fromKeyOrNull))
        }
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
        // 私有资产已经随目录恢复时，不能把另一台设备的旧 content:// URI 写回新数据库。
        imageUri = imageUri?.takeIf { it.isNotBlank() && schemaVersion < 3 },
        imageAssetId = imageAssetId.takeIf { schemaVersion >= 3 },
        imageSourceType = imageSourceType,
        imageProviderKey = imageProviderKey,
        cardOrientation = cardOrientation,
        folderId = folderId,
        createdAtMillis = createdAtMillis,
    )
}

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
    val legacyImageUriCount: Int,
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
 * - [legacyImageUriCount] — 旧备份里仍保存外部图片 URI 的卡数；即使当前切到其他卡面样式，
 *   该引用仍可能是用户数据，UI 据此提示跨设备后可能需要重新选择
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
    val legacyImageUriCount: Int = 0,
)

class BackupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
