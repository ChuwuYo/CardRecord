package com.shuaji.cards.data.backup

import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.TransactionEntity

/**
 * 测试 fixtures：让测试用例构造数据时少写 boilerplate。
 *
 * 字段默认值用「招行 / 运通 / 5 笔 / 红色」——典型国内信用卡的最小集。
 * 不依赖 `System.currentTimeMillis()`，避免 CI 抖动。
 */
object TestData {
    const val FIXED_TIME_MILLIS: Long = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC，固定

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
        validUntilMillis: Long? = null,
        nextDueDateMillis: Long? = null,
        requiredCount: Int = 5,
        colorArgb: Int = 0xFFD32F2F.toInt(),
        note: String = "",
        imageUri: String? = null,
        folderId: Long? = null,
        createdAtMillis: Long = FIXED_TIME_MILLIS,
    ) = CardEntity(
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
}
