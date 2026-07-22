package com.shuaji.cards.data

import com.shuaji.cards.data.local.CardEntity

/** Repository 按当前周期派生出的只读卡片模型，不属于 Room 查询投影。 */
data class CardWithCount(
    val card: CardEntity,
    val currentCount: Int,
    val lastSwipeAtMillis: Long?,
    val cycle: AnnualFeeCycle,
    /** 已解析为应用可读取的私有文件 URI；迁移完成前可暂时是旧 content URI。 */
    val resolvedUserImageUri: String?,
)

/** 单卡编辑等一次性读取场景使用；不触发全表流水聚合。 */
data class StoredCardSnapshot(
    val card: CardEntity,
    val resolvedUserImageUri: String?,
)
