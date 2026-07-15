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
    @DrawableRes val markRes: Int,
) {
    VISA(
        "visa",
        R.string.network_visa,
        R.drawable.visa,
        R.drawable.visa_mark,
    ),
    MASTERCARD(
        "mastercard",
        R.string.network_mastercard,
        R.drawable.mastercard,
        R.drawable.mastercard_mark,
    ),
    UNIONPAY(
        "unionpay",
        R.string.network_unionpay,
        R.drawable.unionpay,
        R.drawable.unionpay_mark,
    ),
    JCB(
        "jcb",
        R.string.network_jcb,
        R.drawable.jcb,
        R.drawable.jcb_mark,
    ),
    AMEX(
        "amex",
        R.string.network_amex,
        R.drawable.americanexpress,
        R.drawable.americanexpress_mark,
    ),
    DINERS(
        "diners",
        R.string.network_diners,
        R.drawable.dinersclub,
        R.drawable.dinersclub_mark,
    ),
    DISCOVER(
        "discover",
        R.string.network_discover,
        R.drawable.discover,
        R.drawable.discover_mark,
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
