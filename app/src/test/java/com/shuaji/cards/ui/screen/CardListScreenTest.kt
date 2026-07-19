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

    @Test
    fun defaultFilters_keepProductOrder() {
        assertEquals(
            listOf(
                CardFilter.All,
                CardFilter.Debit,
                CardFilter.Credit,
                CardFilter.Unfiled,
            ),
            DEFAULT_CARD_FILTERS,
        )
    }

    @Test
    fun folderFilterKey_dependsOnIdentityInsteadOfEditableName() {
        assertEquals(
            cardFilterKey(CardFilter.Folder(folderId = 7, folderName = "旧名称")),
            cardFilterKey(CardFilter.Folder(folderId = 7, folderName = "新名称")),
        )
    }
}
