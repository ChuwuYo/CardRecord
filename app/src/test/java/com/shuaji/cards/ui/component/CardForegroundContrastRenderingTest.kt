package com.shuaji.cards.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.ImageSourceType
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
class CardForegroundContrastRenderingTest {
    @Test
    fun cardNumber_hasVisibleContrastOnWhiteCardSurface() {
        val surface =
            render(CARD_WIDTH, CARD_HEIGHT) {
                CardVisual(
                    card =
                        CardEntity(
                            name = "I",
                            bank = "I",
                            cardNumberMasked = "•••• •••• •••• 4242",
                            requiredCount = 1,
                            colorArgb = Color.White.toArgb(),
                            imageSourceType = ImageSourceType.NONE.key,
                            createdAtMillis = 0L,
                        ),
                    userImageModel = null,
                )
            }

        assertRegionContrast(
            surface = surface,
            region = DpRegion(12.dp, 160.dp, 230.dp, 194.dp),
            maxLuminance = 0.75f,
            minimumPixels = 45,
            message = "白色卡面上的卡号应使用文字阴影保留轮廓",
        )
    }

    @Test
    fun providerRings_haveVisibleContrastOnWhiteCardSurface() {
        val surface = renderProviderDecoration()
        assertRegionContrast(
            surface = surface,
            region = DpRegion(0.dp, 0.dp, 92.dp, 82.dp),
            maxLuminance = 0.95f,
            minimumPixels = 80,
            message = "白色卡面上的相交圆环应保留可见轮廓",
        )
    }

    @Test
    fun providerWatermark_hasVisibleContrastOnWhiteCardSurface() {
        val surface = renderProviderDecoration()

        assertRegionContrast(
            surface = surface,
            region = DpRegion(132.dp, 4.dp, 196.dp, 44.dp),
            maxLuminance = 0.95f,
            minimumPixels = 100,
            message = "白色卡面上的半透明卡组织水印应保留可见轮廓",
        )
    }

    private fun renderProviderDecoration(): RenderedSurface =
        render(DECORATION_WIDTH, DECORATION_HEIGHT) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.White),
            ) {
                ProviderNetworkDecoration(
                    network = CardNetworkProvider.VISA,
                    layout = PROVIDER_LAYOUT,
                )
            }
        }

    @Test
    fun networkBadgeSurface_hasVisibleContrastOnWhiteCardSurface() {
        val surface =
            render(BADGE_CANVAS_WIDTH, BADGE_CANVAS_HEIGHT) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.White),
                ) {
                    NetworkCornerBadge(
                        network = CardNetworkProvider.VISA,
                        layout = BADGE_LAYOUT,
                    )
                }
            }

        assertRegionContrast(
            surface = surface,
            region = DpRegion(36.dp, 44.dp, 39.dp, 64.dp),
            maxLuminance = 0.95f,
            minimumPixels = 30,
            message = "白色卡面上的半透明徽标底板应保持可辨边界",
        )
    }

    private fun render(
        width: Dp,
        height: Dp,
        content: @Composable () -> Unit,
    ): RenderedSurface {
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val density = activity.resources.displayMetrics.density
        val widthPx = (width.value * density).roundToInt()
        val heightPx = (height.value * density).roundToInt()
        val composeView = ComposeView(activity)

        return try {
            activity.setContentView(composeView)
            composeView.setContent {
                MaterialTheme(content = content)
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
            RenderedSurface(bitmap = bitmap, density = density)
        } finally {
            composeView.disposeComposition()
            activityController.pause().stop().destroy()
        }
    }

    private fun assertRegionContrast(
        surface: RenderedSurface,
        region: DpRegion,
        maxLuminance: Float,
        minimumPixels: Int,
        message: String,
    ) {
        val bitmap = surface.bitmap
        val left = (region.left.value * surface.density).roundToInt().coerceIn(0, bitmap.width)
        val top = (region.top.value * surface.density).roundToInt().coerceIn(0, bitmap.height)
        val right = (region.right.value * surface.density).roundToInt().coerceIn(left, bitmap.width)
        val bottom = (region.bottom.value * surface.density).roundToInt().coerceIn(top, bitmap.height)
        val contrastingPixels =
            (left until right).sumOf { x ->
                (top until bottom).count { y ->
                    Color(bitmap.getPixel(x, y)).luminance() < maxLuminance
                }
            }

        assertTrue(
            "$message，实际对比像素数=$contrastingPixels",
            contrastingPixels >= minimumPixels,
        )
    }

    private data class DpRegion(
        val left: Dp,
        val top: Dp,
        val right: Dp,
        val bottom: Dp,
    )

    private data class RenderedSurface(
        val bitmap: Bitmap,
        val density: Float,
    )

    private companion object {
        val CARD_WIDTH = 320.dp
        val CARD_HEIGHT = 204.dp
        val DECORATION_WIDTH = 200.dp
        val DECORATION_HEIGHT = 120.dp
        val BADGE_CANVAS_WIDTH = 100.dp
        val BADGE_CANVAS_HEIGHT = 80.dp

        val PROVIDER_LAYOUT =
            ProviderDecorationLayout(
                cardWidth = DECORATION_WIDTH,
                watermarkWidth = 56.dp,
                watermarkHeight = 32.dp,
                watermarkRight = 8.dp,
                watermarkTop = 8.dp,
                largeRing = RingLayout(diameter = 48.dp, centerX = 30.dp, centerY = 30.dp),
                smallRing = RingLayout(diameter = 36.dp, centerX = 65.dp, centerY = 50.dp),
                ringStroke = 2.dp,
            )
        val BADGE_LAYOUT =
            CardNetworkVisualLayout(
                badgeWidth = 60.dp,
                badgeInset = 6.dp,
                contentEndPadding = 78.dp,
                providerDecoration = PROVIDER_LAYOUT,
            )
    }
}
