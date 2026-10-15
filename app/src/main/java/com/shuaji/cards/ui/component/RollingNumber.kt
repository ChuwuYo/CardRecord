package com.shuaji.cards.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset

const val ROLLING_DIGIT_DURATION_MILLIS = 250

@Composable
fun RollingNumber(
    value: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
) {
    val memory = remember { RollingMemory(value, value.toString().length) }
    val direction = if (value >= memory.value) 1 else -1
    val digits = value.toString()
    val placeCount = maxOf(memory.placeCount, digits.length)
    SideEffect {
        memory.value = value
        memory.placeCount = placeCount
    }
    val digitStyle = style.merge(TextStyle(fontFeatureSettings = "tnum"))
    Row(modifier) {
        for (place in placeCount - 1 downTo 0) {
            key(place) {
                AnimatedContent(
                    targetState = digits.getOrNull(digits.length - 1 - place),
                    transitionSpec = {
                        val spec = tween<IntOffset>(ROLLING_DIGIT_DURATION_MILLIS, easing = FastOutSlowInEasing)
                        val fade = tween<Float>(ROLLING_DIGIT_DURATION_MILLIS, easing = FastOutSlowInEasing)
                        (slideInVertically(spec) { direction * it } + fadeIn(fade)) togetherWith
                            (slideOutVertically(spec) { -direction * it } + fadeOut(fade)) using
                            SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { shown ->
                    if (shown != null) {
                        Text(
                            text = shown.toString(),
                            style = digitStyle,
                            fontWeight = fontWeight,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}

private class RollingMemory(
    var value: Int,
    var placeCount: Int,
)
