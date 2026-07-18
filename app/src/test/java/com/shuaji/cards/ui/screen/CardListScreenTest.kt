package com.shuaji.cards.ui.screen

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CardListScreenTest {
    @Test
    fun gridHeaders_useGroupIdentityInsteadOfDisplayName() {
        val first = CardGridCell.Header(groupKey = "f-1", title = "同名", colorArgb = 0, isUnfiledGroup = false)
        val second = CardGridCell.Header(groupKey = "f-2", title = "同名", colorArgb = 0, isUnfiledGroup = false)

        assertNotEquals(first.key, second.key)
    }

    @Test
    fun topBarHeight_scalesWithBothSubtitleLines() {
        assertEquals(101.dp, resolveListTopBarMinimumHeight(titleLineHeight = 33.dp, subtitleLineHeight = 24.dp))
    }
}
