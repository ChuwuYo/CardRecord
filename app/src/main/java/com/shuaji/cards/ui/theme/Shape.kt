package com.shuaji.cards.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角标准（与真实卡片圆角一致）。
 *
 * Compose 的 dp 是物理尺寸无关单位，不能按屏幕密度把毫米直接换算成不同 dp。
 * 这里的 12dp 是产品视觉 token：接近实体卡的克制圆角，并让卡面与主要容器一致；
 * 它不是对 ISO/IEC 7810 物理半径的精确换算。
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
