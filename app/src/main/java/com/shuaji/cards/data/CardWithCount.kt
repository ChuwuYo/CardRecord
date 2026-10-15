package com.shuaji.cards.data

import com.shuaji.cards.data.local.CardEntity

data class CardWithCount(
    val card: CardEntity,
    val currentCount: Int,
    val lastSwipeAtMillis: Long?,
    val cycle: AnnualFeeCycle,
    val resolvedUserImageUri: String?,
)

data class StoredCardSnapshot(
    val card: CardEntity,
    val resolvedUserImageUri: String?,
)
