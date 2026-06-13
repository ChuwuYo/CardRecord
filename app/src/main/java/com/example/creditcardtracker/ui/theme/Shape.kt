package com.example.creditcardtracker.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 锋锐的圆角：比 Material 3 默认更"切角"，但保持触感的舒适度。
 * 卡片 12.dp，芯片 4.dp，FAB 16.dp。
 */
val CreditCardShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(CornerSize(28.dp)),
    )
