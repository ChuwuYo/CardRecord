package com.shuaji.cards.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 解析 `#RRGGBB` / `#AARRGGBB` 十六进制颜色字符串（纯 Kotlin，不依赖 `android.graphics.Color`）。
 *
 * 非法输入（null、长度不符、含非十六进制字符）一律返回 `null`，由调用方决定回退色。
 */
internal fun parseSeedColor(hex: String?): Color? {
    val cleaned = (hex ?: return null).trim().removePrefix("#")
    val value = cleaned.toLongOrNull(16) ?: return null
    return when (cleaned.length) {
        6 -> Color(0xFF000000L or value) // 补足不透明 alpha
        8 -> Color(value)
        else -> null
    }
}
