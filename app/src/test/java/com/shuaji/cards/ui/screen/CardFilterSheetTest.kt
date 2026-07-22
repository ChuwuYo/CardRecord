package com.shuaji.cards.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.shuaji.cards.R
import com.shuaji.cards.data.local.CardFolderEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardFilterSheetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun manyFolders_keepManagementVisibleAndEveryFolderReachable() {
        var manageCalls = 0
        composeRule.setContent {
            MaterialTheme {
                CardFilterSheetContent(
                    state = ListUiState(folders = folders),
                    onSelectFilter = {},
                    onManageFolders = { manageCalls += 1 },
                )
            }
        }

        val manageLabel = composeRule.activity.getString(R.string.list_filter_manage)
        val scrollHint = composeRule.activity.getString(R.string.list_filter_scroll_hint)
        val lastFolderName = folders.last().name

        composeRule.onNodeWithText(manageLabel).assertIsDisplayed()
        composeRule.onNodeWithText(scrollHint).assertIsDisplayed()
        composeRule.onNodeWithTag(FILTER_OPTIONS_LIST_TAG).assert(hasScrollableContent)
        composeRule
            .onNodeWithTag(FILTER_OPTIONS_LIST_TAG)
            .performScrollToNode(hasText(lastFolderName))
        composeRule.onNodeWithText(lastFolderName).assertIsDisplayed()
        composeRule.onNodeWithText(manageLabel).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, manageCalls) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
    fun compactLandscapeWithLargeText_keepsManagementAndDeepFoldersReachable() {
        val density = composeRule.activity.resources.displayMetrics.density
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = 2f)) {
                MaterialTheme {
                    CardFilterSheetContent(
                        state =
                            ListUiState(
                                folders = folders,
                                filter = CardFilter.Folder(folders.last().id, folders.last().name),
                            ),
                        onSelectFilter = {},
                        onManageFolders = {},
                    )
                }
            }
        }

        val manageLabel = composeRule.activity.getString(R.string.list_filter_manage)
        val scrollHint = composeRule.activity.getString(R.string.list_filter_scroll_hint)
        val lastFolderName = folders.last().name
        val allCardsLabel = composeRule.activity.getString(R.string.list_filter_all)

        composeRule.onNodeWithText(manageLabel).assertIsDisplayed()
        composeRule.onNodeWithText(scrollHint).assertIsDisplayed()
        composeRule.onNodeWithTag(FILTER_OPTIONS_LIST_TAG).assert(hasScrollableContent)
        composeRule.onNodeWithText(lastFolderName).assertIsDisplayed()
        composeRule
            .onNodeWithTag(FILTER_OPTIONS_LIST_TAG)
            .performScrollToNode(hasText(allCardsLabel))
        composeRule.onNodeWithText(allCardsLabel).assertIsDisplayed()
        composeRule.onNodeWithText(manageLabel).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h900dp-port")
    fun tallPhone_stillLimitsFolderViewportAndKeepsTheFifthFolderReachable() {
        val fiveFolders = folders.take(5)
        composeRule.setContent {
            MaterialTheme {
                CardFilterSheetContent(
                    state = ListUiState(folders = fiveFolders),
                    onSelectFilter = {},
                    onManageFolders = {},
                )
            }
        }

        val scrollHint = composeRule.activity.getString(R.string.list_filter_scroll_hint)
        val fifthFolderName = fiveFolders.last().name

        composeRule.onNodeWithText(scrollHint).assertIsDisplayed()
        composeRule.onNodeWithTag(FILTER_OPTIONS_LIST_TAG).assert(hasScrollableContent)
        composeRule
            .onNodeWithTag(FILTER_OPTIONS_LIST_TAG)
            .performScrollToNode(hasText(fifthFolderName))
        composeRule.onNodeWithText(fifthFolderName).assertIsDisplayed()
    }

    private companion object {
        val hasScrollableContent =
            SemanticsMatcher("has scrollable content") { node ->
                node.config[SemanticsProperties.VerticalScrollAxisRange].let { range ->
                    range.maxValue() > 0f
                }
            }

        val folders =
            (1L..8L).map { id ->
                CardFolderEntity(
                    id = id,
                    name = "Folder $id",
                    colorArgb = Color.Blue.toArgb(),
                    createdAtMillis = 0L,
                )
            }
    }
}
