package com.shuaji.cards.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        assertEquals("UNSPECIFIED", CardType.UNSPECIFIED.key)
        assertEquals("DEBIT", CardType.DEBIT.key)
        assertEquals("CREDIT", CardType.CREDIT.key)
        assertEquals(CardType.UNSPECIFIED, CardType.fromKey("FUTURE_CARD_TYPE"))
        assertEquals(null, CardType.fromKeyOrNull("FUTURE_CARD_TYPE"))
    }

    @Test
    fun withNormalizedCreditDetails_enforcesTypeSpecificInvariant() {
        val credit =
            card(
                cardType = CardType.CREDIT.key,
                statementDay = 1,
                repaymentDay = 31,
            ).withNormalizedCreditDetails()
        assertEquals(CardType.CREDIT, credit.cardTypeEnum)
        assertEquals(1, credit.statementDay)
        assertEquals(31, credit.repaymentDay)

        val debit =
            card(
                cardType = CardType.DEBIT.key,
                statementDay = 8,
                repaymentDay = 21,
            ).withNormalizedCreditDetails()
        assertEquals(CardType.DEBIT, debit.cardTypeEnum)
        assertEquals(null, debit.statementDay)
        assertEquals(null, debit.repaymentDay)

        val unknown =
            card(
                cardType = "FUTURE_CARD_TYPE",
                statementDay = 8,
                repaymentDay = 21,
            ).withNormalizedCreditDetails()
        assertEquals(CardType.UNSPECIFIED.key, unknown.cardType)
        assertEquals(null, unknown.statementDay)
        assertEquals(null, unknown.repaymentDay)

        assertThrows(IllegalArgumentException::class.java) {
            card(cardType = CardType.CREDIT.key, statementDay = 0).withNormalizedCreditDetails()
        }
        assertThrows(IllegalArgumentException::class.java) {
            card(cardType = CardType.CREDIT.key, repaymentDay = 32).withNormalizedCreditDetails()
        }
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
        cardType: String = CardType.UNSPECIFIED.key,
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
