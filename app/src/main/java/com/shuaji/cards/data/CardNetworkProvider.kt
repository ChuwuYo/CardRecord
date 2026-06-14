package com.shuaji.cards.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.shuaji.cards.R

/**
 * 全球主流卡组织（Card Network / Card Scheme）。
 *
 * 资源来源：6 个来自 [simple-icons](https://simpleicons.org/)（CC0 1.0 公共领域，
 * 完全免授权），银联 simple-icons 未收录，使用自绘替代。
 *
 * 朝向（横/竖）由用户手动选择，不在卡组织枚举中预判。
 *
 * 显示名通过 [displayNameRes] 走字符串资源，方便后续接 i18n。
 */
enum class CardNetworkProvider(
    val key: String,
    @StringRes val displayNameRes: Int,
    @DrawableRes val logoRes: Int,
    val brandColor: Int,
    val sourceAttribution: String,
) {
    VISA(
        "visa",
        R.string.network_visa,
        R.drawable.visa,
        0xFF1A1F71.toInt(),
        "simple-icons (CC0 1.0)",
    ),
    MASTERCARD(
        "mastercard",
        R.string.network_mastercard,
        R.drawable.mastercard,
        0xFFEB001B.toInt(),
        "simple-icons (CC0 1.0)",
    ),
    UNIONPAY(
        "unionpay",
        R.string.network_unionpay,
        R.drawable.unionpay,
        0xFFE21836.toInt(),
        "self-drawn (simple-icons missing)",
    ),
    JCB(
        "jcb",
        R.string.network_jcb,
        R.drawable.jcb,
        0xFF0B4EA2.toInt(),
        "simple-icons (CC0 1.0)",
    ),
    AMEX(
        "amex",
        R.string.network_amex,
        R.drawable.americanexpress,
        0xFF2E77BC.toInt(),
        "simple-icons (CC0 1.0)",
    ),
    DINERS(
        "diners",
        R.string.network_diners,
        R.drawable.dinersclub,
        0xFF004C97.toInt(),
        "simple-icons (CC0 1.0)",
    ),
    DISCOVER(
        "discover",
        R.string.network_discover,
        R.drawable.discover,
        0xFFFF6000.toInt(),
        "simple-icons (CC0 1.0)",
    ),
    ;

    companion object {
        fun fromKey(key: String?): CardNetworkProvider? =
            if (key.isNullOrBlank()) {
                null
            } else {
                entries.firstOrNull { it.key == key }
            }
    }
}
