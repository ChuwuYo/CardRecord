package com.shuaji.cards.data.backup

import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.data.local.withNormalizedCreditDetails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("settings")
    val settings: BackupSettings = BackupSettings(),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 3
    }
}

@Serializable
data class BackupSettings(
    @SerialName("annualFeeRemindersEnabled")
    val annualFeeRemindersEnabled: Boolean = false,
)

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
        imageSourceType = imageSourceType.key,
        imageProviderKey = imageProviderKey,
        cardOrientation = cardOrientation.key,
        folderId = folderId,
        createdAtMillis = createdAtMillis,
        cardType = normalized.cardType.key,
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
        cardType = resolvedType,
        statementDay = statementDay.takeIf { resolvedType == CardType.CREDIT },
        repaymentDay = repaymentDay.takeIf { resolvedType == CardType.CREDIT },
        validUntilMillis = validUntilMillis,
        nextDueDateMillis = nextDueDateMillis,
        requiredCount = requiredCount,
        colorArgb = colorArgb,
        note = note,
        imageUri = null,
        imageAssetId = imageAssetId,
        imageSourceType = ImageSourceType.fromKey(imageSourceType),
        imageProviderKey = imageProviderKey,
        cardOrientation = CardOrientation.fromKey(cardOrientation),
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

data class BackupPreview(
    val cardCount: Int,
    val folderCount: Int,
    val transactionCount: Int,
    val lastModifiedMillis: Long?,
    val manifestSha256: String,
)

enum class ImportMode { REPLACE, MERGE }

enum class BackupCancelResult {
    CANCELLED,
    COMMIT_IN_PROGRESS,
    NO_ACTIVE_OPERATION,
}

data class ImportResult(
    val cardsAdded: Int,
    val foldersAdded: Int,
    val transactionsAdded: Int,
    val transactionsSkipped: Int = 0,
    val cardsSkippedInvalidFolder: Int = 0,
    val duplicateFolderNames: Int = 0,
    val duplicateCardNames: Int = 0,
    val annualFeeRemindersEnabled: Boolean = false,
)

class BackupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
