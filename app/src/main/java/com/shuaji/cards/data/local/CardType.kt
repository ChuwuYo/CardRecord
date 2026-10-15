package com.shuaji.cards.data.local

internal const val CARD_TYPE_UNSPECIFIED_KEY = "UNSPECIFIED"

enum class CardType(
    val key: String,
) {
    UNSPECIFIED(CARD_TYPE_UNSPECIFIED_KEY),
    DEBIT("DEBIT"),
    CREDIT("CREDIT"),
    ;

    companion object {
        fun fromKey(key: String): CardType = fromKeyOrNull(key) ?: UNSPECIFIED

        fun fromKeyOrNull(key: String): CardType? = entries.firstOrNull { it.key == key }
    }
}

fun Int.isValidCardMonthDay(): Boolean = this in 1..31

fun CardEntity.withNormalizedCreditDetails(): CardEntity =
    if (cardType == CardType.CREDIT) {
        require(statementDay == null || statementDay.isValidCardMonthDay()) {
            "statementDay must be null or in 1..31"
        }
        require(repaymentDay == null || repaymentDay.isValidCardMonthDay()) {
            "repaymentDay must be null or in 1..31"
        }
        this
    } else {
        copy(
            statementDay = null,
            repaymentDay = null,
        )
    }
