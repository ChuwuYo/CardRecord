package com.shuaji.cards.ui.screen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.ImageSourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CardEditViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: CardRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository =
            CardRepository(
                database = db,
                cardDao = db.cardDao(),
                transactionDao = db.transactionDao(),
                folderDao = db.cardFolderDao(),
                clock = Clock.systemUTC(),
                zoneIdProvider = { ZoneOffset.UTC },
                boundaryTicks = emptyFlow(),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun obsoleteImageUris_keepsOnlyCommittedImage() {
        val obsolete =
            obsoleteImageUris(
                originalUri = "content://old",
                acquiredUris = setOf("content://new-1", "content://new-2"),
                retainedUri = "content://new-2",
            )

        assertEquals(setOf("content://old", "content://new-1"), obsolete)
    }

    @Test
    fun obsoleteImageUris_onCancelKeepsOriginalImage() {
        val obsolete =
            obsoleteImageUris(
                originalUri = "content://old",
                acquiredUris = setOf("content://new-1", "content://new-2"),
                retainedUri = "content://old",
            )

        assertEquals(setOf("content://new-1", "content://new-2"), obsolete)
    }

    @Test
    fun persistedImageUri_clearsUriForNonUserSources() {
        assertNull(persistedImageUri(ImageSourceType.PROVIDER, "content://old"))
        assertNull(persistedImageUri(ImageSourceType.NONE, "content://old"))
        assertEquals("content://old", persistedImageUri(ImageSourceType.USER, "content://old"))
    }

    @Test
    fun releasableImageUris_excludesUrisReferencedByOtherCards() {
        assertEquals(
            setOf("content://unused"),
            releasableImageUris(
                candidates = setOf("content://shared", "content://unused"),
                referencedUris = setOf("content://shared"),
            ),
        )
    }

    @Test
    fun savingState_disablesAdditionalSaveAttempts() {
        val state = CardEditUiState(name = "测试卡", requiredCount = "6", isSaving = true)

        assertEquals(false, state.canSave)
    }

    @Test
    fun repeatedSaveWhileInFlight_insertsOnlyOneCard() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.update { it.copy(name = "防重复卡", requiredCount = "6") }

            viewModel.save()
            viewModel.save()
            advanceUntilIdle()
            viewModel.uiState.first { it.saved }

            assertEquals(1, db.cardDao().listAll().size)
        }

    @Test
    fun closingFirst_preventsSaveFromStarting() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.update { it.copy(name = "关闭中的卡", requiredCount = "6") }

            assertNotNull(viewModel.beginClosing())
            viewModel.save()
            advanceUntilIdle()

            assertEquals(0, db.cardDao().listAll().size)
        }

    @Test
    fun savingFirst_preventsCloseFromStarting() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.update { it.copy(name = "保存中的卡", requiredCount = "6") }

            viewModel.save()

            assertNull(viewModel.beginClosing())
            viewModel.uiState.first { it.saved }
            assertEquals(1, db.cardDao().listAll().size)
        }
}
