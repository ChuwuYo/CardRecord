package com.shuaji.cards.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.ColorEnvelope
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.shuaji.cards.R

/**
 * 现代化调色板：HSV 圆形色环 + 下方 Brightness 滑动条 + 当前色预览 + HEX 实时显示。
 *
 * 基于 [skydoves/colorpicker-compose](https://github.com/skydoves/colorpicker-compose)
 * （730 stars，Kotlin Multiplatform 支持，活跃维护）。
 */
@Composable
fun ModernColorPicker(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = rememberColorPickerController()
    var currentColor by remember { mutableStateOf(initialColor) }

    Column(modifier = modifier.fillMaxWidth()) {
        // 圆形 HSV 色板
        HsvColorPicker(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(8.dp),
            controller = controller,
            initialColor = initialColor,
            onColorChanged = { envelope: ColorEnvelope ->
                currentColor = envelope.color
                onColorSelected(envelope.color)
            },
        )

        Spacer(Modifier.height(8.dp))

        // 当前色 + HEX
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        ),
            )
            Column {
                Text(
                    stringResource(R.string.edit_color_hex_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "#%02X%02X%02X".format(
                        (currentColor.red * 255).toInt(),
                        (currentColor.green * 255).toInt(),
                        (currentColor.blue * 255).toInt(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 亮度 / 明度滑动条
        BrightnessSlider(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(horizontal = 4.dp),
            controller = controller,
        )
    }
}
