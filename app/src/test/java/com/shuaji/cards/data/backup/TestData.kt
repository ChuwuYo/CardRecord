package com.shuaji.cards.data.backup

import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.TransactionEntity

/** 测试数据工厂。固定时间用于保证测试结果可重复。 */
object TestData {
    const val FIXED_TIME_MILLIS: Long = 1_700_000_000_000L

    fun folder(
        id: Long = 0L,
        name: String = "默认分组",
        colorArgb: Int = 0xFF1976D2.toInt(),
        sortOrder: Int = 0,
        createdAtMillis: Long = FIXED_TIME_MILLIS,
    ) = CardFolderEntity(
        id = id,
        name = name,
        colorArgb = colorArgb,
        sortOrder = sortOrder,
        createdAtMillis = createdAtMillis,
    )

    fun card(
        id: Long = 0L,
        name: String = "招行经典白",
        bank: String = "招商银行",
        cardNumberMasked: String = "**** **** **** 1234",
        cardType: String = CardType.UNSPECIFIED.key,
        statementDay: Int? = null,
        repaymentDay: Int? = null,
        validUntilMillis: Long? = null,
        nextDueDateMillis: Long? = null,
        requiredCount: Int = 5,
        colorArgb: Int = 0xFFD32F2F.toInt(),
        note: String = "",
        imageUri: String? = null,
        imageSourceType: String = "NONE",
        imageProviderKey: String? = null,
        cardOrientation: String = "LANDSCAPE",
        folderId: Long? = null,
        createdAtMillis: Long = FIXED_TIME_MILLIS,
    ) = CardEntity(
        id = id,
        name = name,
        bank = bank,
        cardNumberMasked = cardNumberMasked,
        cardType = cardType,
        statementDay = statementDay,
        repaymentDay = repaymentDay,
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

    fun transaction(
        id: Long = 0L,
        cardId: Long,
        occurredAtMillis: Long = FIXED_TIME_MILLIS,
    ) = TransactionEntity(
        id = id,
        cardId = cardId,
        occurredAtMillis = occurredAtMillis,
    )

    fun backupBundle(
        version: Int = BackupBundle.SCHEMA_VERSION,
        cards: List<CardEntity> = emptyList(),
        folders: List<CardFolderEntity> = emptyList(),
        transactions: List<TransactionEntity> = emptyList(),
    ): BackupBundle =
        BackupBundle(
            version = version,
            cards = cards.map { it.toBackup() },
            folders = folders.map { it.toBackup() },
            transactions = transactions.map { it.toBackup() },
        )
}
