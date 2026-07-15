package com.shuaji.cards.ui.screen

import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.AnnualFeeCycleState
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.TransactionEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CardDetailViewModelTest {
    @Test
    fun detailSnapshot_marksOnlyWindowRowsAsCurrent() {
        val start = LocalDate.of(2027, 6, 1)
        val ui =
            CardDetailUi(
                card = CardEntity(name = "卡", bank = "", cardNumberMasked = "", requiredCount = 5, colorArgb = 0),
                currentCount = 1,
                isExpired = false,
                lastSwipeAtMillis = null,
                cycle =
                    AnnualFeeCycle(
                        state = AnnualFeeCycleState.ACTIVE,
                        startDate = start,
                        startBoundaryMillis = Instant.parse("2027-06-01T00:00:00Z").toEpochMilli(),
                        dueBoundaryMillis = Instant.parse("2028-06-01T00:00:00Z").toEpochMilli(),
                    ),
            )

        assertTrue(ui.isCurrentPeriod(transaction("2027-07-01T00:00:00Z")))
        assertFalse(ui.isCurrentPeriod(transaction("2026-07-01T00:00:00Z")))
    }

    private fun transaction(instant: String) = TransactionEntity(cardId = 1, occurredAtMillis = Instant.parse(instant).toEpochMilli())
}
