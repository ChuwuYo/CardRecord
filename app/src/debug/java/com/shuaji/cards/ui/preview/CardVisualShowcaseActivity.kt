package com.shuaji.cards.ui.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shuaji.cards.R
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.ui.component.CardVisual
import com.shuaji.cards.ui.theme.ShuajiTheme

/** 只存在于 Debug APK 的卡面视觉验收页。 */
class CardVisualShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShuajiTheme {
                CardVisualShowcase()
            }
        }
    }
}

@Composable
private fun CardVisualShowcase() {
    val sampleBank = stringResource(R.string.card_visual_sample_bank)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShowcaseLabel(R.string.card_visual_showcase_pure)
        CardVisual(
            card =
                showcaseCard(
                    name = stringResource(R.string.card_visual_sample_pure_name),
                    bank = sampleBank,
                    source = ImageSourceType.NONE,
                    network = CardNetworkProvider.VISA.key,
                ),
        )

        ShowcaseLabel(R.string.card_visual_showcase_provider_long)
        CardVisual(
            card =
                showcaseCard(
                    name = stringResource(R.string.card_visual_sample_provider_name),
                    bank = stringResource(R.string.card_visual_sample_provider_bank),
                    number = "•••• •••• •••• 4832",
                    source = ImageSourceType.PROVIDER,
                    network = CardNetworkProvider.VISA.key,
                ),
        )

        ShowcaseLabel(R.string.card_visual_showcase_compact)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardVisual(
                card =
                    showcaseCard(
                        name = stringResource(R.string.card_visual_sample_mastercard_name),
                        bank = sampleBank,
                        source = ImageSourceType.PROVIDER,
                        network = CardNetworkProvider.MASTERCARD.key,
                    ),
                modifier = Modifier.width(160.dp),
            )
            CardVisual(
                card =
                    showcaseCard(
                        name = stringResource(R.string.card_visual_sample_unionpay_name),
                        bank = sampleBank,
                        source = ImageSourceType.PROVIDER,
                        network = CardNetworkProvider.UNIONPAY.key,
                    ),
                modifier = Modifier.width(160.dp),
            )
        }

        ShowcaseLabel(R.string.card_visual_showcase_user)
        CardVisual(
            card =
                showcaseCard(
                    name = stringResource(R.string.card_visual_sample_user_name),
                    bank = sampleBank,
                    source = ImageSourceType.USER,
                    network = CardNetworkProvider.MASTERCARD.key,
                    imageUri = "android.resource://com.shuaji.cards/${R.drawable.unionpay}",
                ),
        )

        ShowcaseLabel(R.string.card_visual_showcase_portrait)
        Box(modifier = Modifier.fillMaxWidth()) {
            CardVisual(
                card =
                    showcaseCard(
                        name = stringResource(R.string.card_visual_sample_portrait_name),
                        bank = sampleBank,
                        source = ImageSourceType.PROVIDER,
                        network = CardNetworkProvider.VISA.key,
                        orientation = CardOrientation.PORTRAIT,
                    ),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ShowcaseLabel(
    @StringRes textRes: Int,
) {
    Text(
        text = stringResource(textRes),
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.labelLarge,
    )
}

private fun showcaseCard(
    name: String,
    bank: String,
    number: String = "•••• 2048",
    source: ImageSourceType,
    network: String?,
    imageUri: String? = null,
    orientation: CardOrientation = CardOrientation.LANDSCAPE,
): CardEntity =
    CardEntity(
        name = name,
        bank = bank,
        cardNumberMasked = number,
        requiredCount = 5,
        colorArgb = 0xFF184A68.toInt(),
        imageUri = imageUri,
        imageSourceType = source.name,
        imageProviderKey = network,
        cardOrientation = orientation.name,
    )
