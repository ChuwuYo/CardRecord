package com.example.creditcardtracker.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 给可水平滚动的内容（LazyRow 等）加左右两侧的渐隐 mask。
 *
 * 用 [BlendMode.DstIn] 把两侧 [fadeWidth] 宽的区域内 content 的 alpha
 * 渐变到 0 —— 比单纯叠半透明黑层更"擦掉"内容，提示"这里还有更多，可滑动"。
 *
 * - 左右两侧都加渐隐，对称；只滚动一端也能感知
 * - 强度由 [fadeWidth] 控制（默认 28.dp，约占 1/12 屏宽）
 * - 用 [Modifier.drawWithContent] 实现，**不影响布局/测量**——只是绘制阶段 mask
 */
fun Modifier.horizontalEdgeFade(fadeWidth: Dp = 28.dp): Modifier =
    this.drawWithContent {
        drawContent()
        val fadePx = fadeWidth.toPx()
        // 左：0→黑（即在左侧 fadeWidth 内把 source alpha 从 0 提到 1）
        drawRect(
            brush =
                Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = fadePx,
                ),
            blendMode = BlendMode.DstIn,
        )
        // 右：黑→0
        drawRect(
            brush =
                Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - fadePx,
                    endX = size.width,
                ),
            blendMode = BlendMode.DstIn,
        )
    }
