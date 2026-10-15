package com.shuaji.cards.ui.theme

import androidx.compose.ui.graphics.Color

internal fun parseSeedColor(hex: String?): Color? {
    val cleaned = (hex ?: return null).trim().removePrefix("#")
    val value = cleaned.toLongOrNull(16) ?: return null
    return when (cleaned.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> null
    }
}
