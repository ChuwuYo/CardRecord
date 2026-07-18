package com.shuaji.cards.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardEntityTest {
    @Test
    fun imageSourceTypeEnum_parsesKnownValueAndFallsBackForUnknownValue() {
        assertEquals(ImageSourceType.PROVIDER, card(imageSourceType = "PROVIDER").imageSourceTypeEnum)
        assertEquals(ImageSourceType.NONE, card(imageSourceType = "FUTURE_TYPE").imageSourceTypeEnum)
    }

    @Test
    fun isExpiredAt_usesStrictlyAfterBoundary() {
        val card = card(validUntilMillis = 1_000L)

        assertFalse(card.isExpiredAt(999L))
        assertFalse(card.isExpiredAt(1_000L))
        assertTrue(card.isExpiredAt(1_001L))
        assertFalse(card(validUntilMillis = null).isExpiredAt(Long.MAX_VALUE))
    }

    private fun card(
        imageSourceType: String = ImageSourceType.NONE.name,
        validUntilMillis: Long? = null,
    ) = CardEntity(
        name = "测试卡",
        bank = "测试银行",
        cardNumberMasked = "**** 1234",
        validUntilMillis = validUntilMillis,
        requiredCount = 6,
        colorArgb = 0,
        imageSourceType = imageSourceType,
    )
}
