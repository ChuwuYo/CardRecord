package com.shuaji.cards.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CardEntityTest {
    @Test
    fun enumConverters_roundTripStableKeysAndFallBackForUnknownKeys() {
        CardType.entries.forEach {
            assertEquals(it, CardEnumConverters.keyToCardType(CardEnumConverters.cardTypeToKey(it)))
        }
        ImageSourceType.entries.forEach {
            assertEquals(it, CardEnumConverters.keyToImageSourceType(CardEnumConverters.imageSourceTypeToKey(it)))
        }
        CardOrientation.entries.forEach {
            assertEquals(it, CardEnumConverters.keyToCardOrientation(CardEnumConverters.cardOrientationToKey(it)))
        }
        assertEquals("PROVIDER", CardEnumConverters.imageSourceTypeToKey(ImageSourceType.PROVIDER))
        assertEquals("PORTRAIT", CardEnumConverters.cardOrientationToKey(CardOrientation.PORTRAIT))
        assertEquals("CREDIT", CardEnumConverters.cardTypeToKey(CardType.CREDIT))
        assertEquals(ImageSourceType.NONE, CardEnumConverters.keyToImageSourceType("FUTURE_TYPE"))
        assertEquals(CardOrientation.LANDSCAPE, CardEnumConverters.keyToCardOrientation("FUTURE_ORIENTATION"))
        assertEquals(CardType.UNSPECIFIED, CardEnumConverters.keyToCardType("FUTURE_CARD_TYPE"))
    }

    private fun card(
        imageSourceType: ImageSourceType = ImageSourceType.NONE,
        cardType: CardType = CardType.UNSPECIFIED,
        statementDay: Int? = null,
        repaymentDay: Int? = null,
        validUntilMillis: Long? = null,
    ) = CardEntity(
        name = "测试卡",
        bank = "测试银行",
        cardNumberMasked = "**** 1234",
        cardType = cardType,
        statementDay = statementDay,
        repaymentDay = repaymentDay,
        validUntilMillis = validUntilMillis,
        requiredCount = 6,
        colorArgb = 0,
        imageSourceType = imageSourceType,
    )
}
