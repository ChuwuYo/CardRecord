package com.example.creditcardtracker.data

import androidx.annotation.DrawableRes
import com.example.creditcardtracker.R
import com.example.creditcardtracker.data.local.CardOrientation

/**
 * 全球主流卡组织（Card Network / Card Scheme）。
 *
 * 选卡面时只显示卡组织——因为同一卡组织下不同银行的卡面设计差异很大，
 * 不可能把所有银行的卡面都硬编码进 APP。卡组织图标是通用标识，
 * 用户再叠加自定义卡图（可选）。
 */
enum class CardNetworkProvider(
    val key: String,
    val displayName: String,
    @DrawableRes val logoRes: Int,
    val brandColor: Int,
    val defaultOrientation: CardOrientation,
) {
    VISA("visa", "Visa", R.drawable.card_visa, 0xFF1A1F71.toInt(), CardOrientation.LANDSCAPE),
    MASTERCARD("mastercard", "MasterCard", R.drawable.card_mastercard, 0xFFEB001B.toInt(), CardOrientation.LANDSCAPE),
    UNIONPAY("unionpay", "银联 UnionPay", R.drawable.card_unionpay, 0xFFE21836.toInt(), CardOrientation.LANDSCAPE),
    JCB("jcb", "JCB", R.drawable.card_jcb, 0xFF0E4C96.toInt(), CardOrientation.LANDSCAPE),
    AMEX("amex", "American Express 运通", R.drawable.card_amex, 0xFF006FCF.toInt(), CardOrientation.PORTRAIT),
    DINERS("diners", "Diners Club 大来", R.drawable.card_diners, 0xFF0079BE.toInt(), CardOrientation.PORTRAIT),
    DISCOVER("discover", "Discover", R.drawable.card_discover, 0xFFFF6000.toInt(), CardOrientation.LANDSCAPE),
    QUICKPASS("quickpass", "银联闪付 QuickPass", R.drawable.card_quickpass, 0xFFE21836.toInt(), CardOrientation.LANDSCAPE),
    ;

    companion object {
        fun fromKey(key: String?): CardNetworkProvider? = if (key.isNullOrBlank()) null else entries.firstOrNull { it.key == key }
    }
}
