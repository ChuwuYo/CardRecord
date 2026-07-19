package com.shuaji.cards.data.local

internal const val CARD_TYPE_UNSPECIFIED_KEY = "UNSPECIFIED"

/**
 * 卡片的资金属性。持久化只使用 [key]，不能依赖枚举名称作为数据库或备份协议。
 *
 * [UNSPECIFIED] 是兼容旧卡与允许用户暂不选择的真实业务状态，不能推断成信用卡。
 */
enum class CardType(
    val key: String,
) {
    UNSPECIFIED(CARD_TYPE_UNSPECIFIED_KEY),
    DEBIT("DEBIT"),
    CREDIT("CREDIT"),
    ;

    companion object {
        /** 内部读取未知值时安全回退；外部导入必须改用 [fromKeyOrNull] 并拒绝未知值。 */
        fun fromKey(key: String): CardType = fromKeyOrNull(key) ?: UNSPECIFIED

        fun fromKeyOrNull(key: String): CardType? = entries.firstOrNull { it.key == key }
    }
}

val CardEntity.cardTypeEnum: CardType
    get() = CardType.fromKey(cardType)

/** 账单日和还款日都是每月的日号，不携带月份或时区语义。 */
fun Int.isValidCardMonthDay(): Boolean = this in 1..31

/**
 * 保存边界使用的统一归一化：只有信用卡保留账单日和还款日；信用卡的非空日号必须合法。
 * 外部导入应先完成结构校验并映射成自己的错误类型，不能把这里的参数异常泄漏给 UI。
 */
fun CardEntity.withNormalizedCreditDetails(): CardEntity {
    val normalizedType = cardTypeEnum
    return if (normalizedType == CardType.CREDIT) {
        require(statementDay == null || statementDay.isValidCardMonthDay()) {
            "statementDay must be null or in 1..31"
        }
        require(repaymentDay == null || repaymentDay.isValidCardMonthDay()) {
            "repaymentDay must be null or in 1..31"
        }
        copy(cardType = normalizedType.key)
    } else {
        copy(
            cardType = normalizedType.key,
            statementDay = null,
            repaymentDay = null,
        )
    }
}
