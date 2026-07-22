package com.shuaji.cards.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.shuaji.cards.data.local.CardEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BankLabelRenderingTest {
    @Test
    fun bankIcon_hasVisibleContrastOnWhiteCardSurface() {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val density = activity.resources.displayMetrics.density
        val widthPx = (LABEL_WIDTH.value * density).roundToInt()
        val heightPx = (LABEL_HEIGHT.value * density).roundToInt()
        val iconRegionEnd = (ICON_REGION_WIDTH.value * density).roundToInt()
        val composeView = ComposeView(activity)

        try {
            activity.setContentView(composeView)
            composeView.setContent {
                MaterialTheme {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.White),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BankLabel(
                            card = testCard,
                            modifier = Modifier.width(LABEL_WIDTH),
                        )
                    }
                }
            }

            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            composeView.layout(0, 0, widthPx, heightPx)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            composeView.draw(Canvas(bitmap))
            val contrastingPixels =
                (0 until iconRegionEnd).sumOf { x ->
                    (0 until heightPx).count { y ->
                        Color(bitmap.getPixel(x, y)).luminance() < 0.9f
                    }
                }

            assertTrue(
                "白色卡面上的银行图标应保留可见轮廓，实际对比像素数=$contrastingPixels",
                contrastingPixels >= MIN_CONTRASTING_PIXELS,
            )
        } finally {
            composeView.disposeComposition()
            activityController.pause().stop().destroy()
        }
    }

    private companion object {
        const val MIN_CONTRASTING_PIXELS = 40
        val LABEL_WIDTH = 120.dp
        val LABEL_HEIGHT = 32.dp
        val ICON_REGION_WIDTH = 18.dp

        val testCard =
            CardEntity(
                name = "Test Card",
                bank = "Test Bank",
                cardNumberMasked = "",
                requiredCount = 1,
                colorArgb = Color.White.toArgb(),
                createdAtMillis = 0L,
            )
    }
}
