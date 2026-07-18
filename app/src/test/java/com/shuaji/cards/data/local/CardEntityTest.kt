package com.shuaji.cards.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CardEntityTest {
    @Test
    fun imageSourceTypeEnum_parsesKnownValueAndFallsBackForUnknownValue() {
        assertEquals(ImageSourceType.PROVIDER, card(imageSourceType = ImageSourceType.PROVIDER.key).imageSourceTypeEnum)
        assertEquals(ImageSourceType.NONE, card(imageSourceType = "FUTURE_TYPE").imageSourceTypeEnum)
    }

    @Test
    fun persistedEnumKeys_areStableAndUnknownValuesHaveExplicitFallbacks() {
        assertEquals("USER", ImageSourceType.USER.key)
        assertEquals(ImageSourceType.NONE, ImageSourceType.fromKey("FUTURE_TYPE"))
        assertEquals("PORTRAIT", CardOrientation.PORTRAIT.key)
        assertEquals(CardOrientation.LANDSCAPE, CardOrientation.fromKey("FUTURE_ORIENTATION"))
    }

    @Test
    fun isExpiredAt_usesTheUsersLocalCalendarDate() {
        val card =
            card(
                validUntilMillis =
                    com.shuaji.cards.data.DateToken.fromLocalDate(
                        java.time.LocalDate.of(2028, 2, 28),
                    ),
            )
        val seoul = ZoneId.of("Asia/Seoul")

        assertFalse(card.isExpiredAt(Instant.parse("2028-02-28T14:59:59Z"), seoul))
        assertTrue(card.isExpiredAt(Instant.parse("2028-02-28T15:00:00Z"), seoul))
        assertFalse(card(validUntilMillis = null).isExpiredAt(Instant.MAX, seoul))
    }

    private fun card(
        imageSourceType: String = ImageSourceType.NONE.key,
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
