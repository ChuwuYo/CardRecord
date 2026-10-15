package com.shuaji.cards.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot

internal const val THEME_REVEAL_DURATION_MILLIS = 400
private const val THEME_SNAPSHOT_TIMEOUT_MILLIS = 250L

private class PressPosition {
    var value: Offset = Offset.Unspecified
}

private class RevealOverlay(
    val snapshot: ImageBitmap,
    val center: Offset,
)

@Composable
fun <T> ThemeSwitchTransition(
    target: T,
    content: @Composable (shown: T) -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    var overlay by remember { mutableStateOf<RevealOverlay?>(null) }
    val radius = remember { Animatable(0f) }
    val layer = rememberGraphicsLayer()
    val lastPress = remember { PressPosition() }
    val holePath = remember { Path() }

    LaunchedEffect(target) {
        if (target == shown) return@LaunchedEffect
        val size = layer.size.toSize()
        val snapshot =
            if (size.isEmpty()) {
                null
            } else {
                try {
                    withTimeoutOrNull(THEME_SNAPSHOT_TIMEOUT_MILLIS) { layer.toImageBitmap() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        if (snapshot == null) {
            shown = target
            return@LaunchedEffect
        }
        val center = lastPress.value.takeIf { it.isSpecified && size.toRect().contains(it) } ?: size.center
        try {
            radius.snapTo(0f)
            overlay = RevealOverlay(snapshot, center)
            shown = target
            radius.animateTo(
                targetValue = size.farthestCornerDistance(center),
                animationSpec = tween(THEME_REVEAL_DURATION_MILLIS, easing = EaseInOutQuad),
            )
        } finally {
            overlay = null
        }
    }

    Box(
        modifier =
            Modifier
                .pointerInput(Unit) {
                    awaitEachGesture {
                        lastPress.value = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                    }
                }.drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                    val current = overlay ?: return@drawWithContent
                    holePath.rewind()
                    holePath.addOval(Rect(current.center, radius.value))
                    clipPath(holePath, ClipOp.Difference) { drawImage(current.snapshot) }
                },
    ) {
        content(shown)
    }
}

private fun Size.toRect(): Rect = Rect(Offset.Zero, this)

private fun Size.farthestCornerDistance(point: Offset): Float =
    maxOf(
        hypot(point.x, point.y),
        hypot(width - point.x, point.y),
        hypot(point.x, height - point.y),
        hypot(width - point.x, height - point.y),
    )
