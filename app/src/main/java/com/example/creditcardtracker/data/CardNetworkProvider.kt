package com.example.creditcardtracker.data

import androidx.annotation.DrawableRes
import com.example.creditcardtracker.R
import com.example.creditcardtracker.data.local.CardOrientation

/**
 * 全球主流卡组织（Card Network / Card Scheme）。
 *
 * 选卡面时只显示卡组织——因为同一卡组织下不同银行的卡面设计差异很大，
 * 不可能把所有银行的卡面都硬编码进 APP。卡组织 logo 是通用标识。
 *
 * 资源来源：6 个走 [simple-icons](https://simpleicons.org) （CC0 1.0 公共领域，
 * 完全免授权），银联 / 银联闪付 simple-icons 未收录，使用自绘替代。
 */
enum class CardNetworkProvider(
    val key: String,
    val displayName: String,
    @DrawableRes val logoRes: Int,
    val brandColor: Int,
    val defaultOrientation: CardOrientation,
    val sourceAttribution: String,
) {
    VISA(
        "visa",
        "Visa",
        R.drawable.visa,
        0xFF1A1F71.toInt(),
        CardOrientation.LANDSCAPE,
        "simple-icons (CC0 1.0)",
    ),
    MASTERCARD(
        "mastercard",
        "MasterCard",
        R.drawable.mastercard,
        0xFFEB001B.toInt(),
        CardOrientation.LANDSCAPE,
        "simple-icons (CC0 1.0)",
    ),
    UNIONPAY(
        "unionpay",
        "银联 UnionPay",
        R.drawable.card_unionpay,
        0xFFE21836.toInt(),
        CardOrientation.LANDSCAPE,
        "自绘（simple-icons 未收录）",
    ),
    JCB(
        "jcb",
        "JCB",
        R.drawable.jcb,
        0xFF0B4EA2.toInt(),
        CardOrientation.LANDSCAPE,
        "simple-icons (CC0 1.0)",
    ),
    AMEX(
        "amex",
        "American Express 运通",
        R.drawable.americanexpress,
        0xFF2E77BC.toInt(),
        CardOrientation.PORTRAIT,
        "simple-icons (CC0 1.0)",
    ),
    DINERS(
        "diners",
        "Diners Club 大来",
        R.drawable.dinersclub,
        0xFF004C97.toInt(),
        CardOrientation.PORTRAIT,
        "simple-icons (CC0 1.0)",
    ),
    DISCOVER(
        "discover",
        "Discover",
        R.drawable.discover,
        0xFFFF6000.toInt(),
        CardOrientation.LANDSCAPE,
        "simple-icons (CC0 1.0)",
    ),
    QUICKPASS(
        "quickpass",
        "银联闪付 QuickPass",
        R.drawable.card_quickpass,
        0xFFE21836.toInt(),
        CardOrientation.LANDSCAPE,
        "自绘（simple-icons 未收录）",
    ),
    ;

    companion object {
        fun fromKey(key: String?): CardNetworkProvider? = if (key.isNullOrBlank()) null else entries.firstOrNull { it.key == key }
    }
}
