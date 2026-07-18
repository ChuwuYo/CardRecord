package com.shuaji.cards.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class CardListItemTest {
    @Test
    fun portraitWidth_isDelegatedEntirelyToCardVisual() {
        assertEquals(1f, resolveCardVisualWidthFraction(compact = false, portrait = true))
    }

    @Test
    fun landscapeWidth_preservesListVariants() {
        assertEquals(1f, resolveCardVisualWidthFraction(compact = true, portrait = false))
        assertEquals(0.88f, resolveCardVisualWidthFraction(compact = false, portrait = false))
    }
}
