package com.shuaji.cards.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角标准（与真实卡片圆角一致）。
 *
 * 依据：ISO/IEC 7810 ID-1 卡的圆角半径为 3.18 mm。
 * - 在 xhdpi (1mm ≈ 3dp)：3.18 × 3 ≈ 9.5dp
 * - 在 xxhdpi (1mm ≈ 4dp)：3.18 × 4 ≈ 12.7dp
 * 综合中端设备取 12dp 作为卡面与大部分组件的标准圆角。
 *
 * - [extraSmall] = 4dp  → chip、状态点
 * - [small]      = 8dp  → 小型 surface、小图标容器
 * - [medium]     = 12dp → **卡面 / 卡片容器 / 按钮**（卡标准）
 * - [large]      = 12dp → 与 medium 保持一致，避免大面板意外变圆
 * - [extraLarge] = 16dp → dialog、bottom sheet、扩展 FAB
 */
val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(16.dp),
    )
