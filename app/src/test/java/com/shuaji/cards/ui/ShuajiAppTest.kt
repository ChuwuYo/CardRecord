package com.shuaji.cards.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ShuajiAppTest {
    @Test
    fun globalSnackbar_clearsFloatingActionButtons() {
        assertEquals(88.dp, globalSnackbarBottomPadding(Routes.LIST))
        assertEquals(88.dp, globalSnackbarBottomPadding(Routes.DETAIL))
    }

    @Test
    fun globalSnackbar_usesNormalInsetOnScreensWithoutFloatingActionButton() {
        assertEquals(16.dp, globalSnackbarBottomPadding(Routes.CREATE))
        assertEquals(16.dp, globalSnackbarBottomPadding(Routes.SETTINGS))
        assertEquals(16.dp, globalSnackbarBottomPadding(null))
    }
}
