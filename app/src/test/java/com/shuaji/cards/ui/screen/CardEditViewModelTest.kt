package com.shuaji.cards.ui.screen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.ImageSourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
                boundaryTicks = flowOf(Unit),
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
    fun savedState_disablesAdditionalSaveAttempts() {
        val state =
            CardEditUiState(
                name = "测试卡",
                requiredCount = "6",
                saveResult = CardEditSaveResult.Saved(1L),
            )

        assertEquals(false, state.canSave)
    }

    @Test
    fun selectingPlainOrUserStyle_preservesNetwork() {
        val viewModel = CardEditViewModel(repository)
        viewModel.selectNetwork(CardNetworkProvider.MASTERCARD)

        viewModel.selectImageSource(ImageSourceType.NONE)
        assertEquals(CardNetworkProvider.MASTERCARD.key, viewModel.uiState.value.imageProviderKey)

        viewModel.selectImageSource(ImageSourceType.USER)
        assertEquals(CardNetworkProvider.MASTERCARD.key, viewModel.uiState.value.imageProviderKey)
    }

    @Test
    fun networkPickerPresentation_hidesUserImageWithoutDroppingSelection() {
        val selectedKey = CardNetworkProvider.MASTERCARD.key

        val presentation = resolveCardNetworkPickerPresentation(ImageSourceType.USER, selectedKey)

        assertFalse(presentation.visible)
        assertEquals(selectedKey, presentation.selectedKey)
    }

    @Test
    fun networkPickerPresentation_restoresSelectionAfterUserImage() {
        val selectedKey = CardNetworkProvider.MASTERCARD.key
        val hidden = resolveCardNetworkPickerPresentation(ImageSourceType.USER, selectedKey)

        val plain = resolveCardNetworkPickerPresentation(ImageSourceType.NONE, hidden.selectedKey)
        val provider = resolveCardNetworkPickerPresentation(ImageSourceType.PROVIDER, hidden.selectedKey)

        assertTrue(plain.visible)
        assertEquals(selectedKey, plain.selectedKey)
        assertTrue(provider.visible)
        assertEquals(selectedKey, provider.selectedKey)
    }

    @Test
    fun clearingNetworkWhileProviderStyle_fallsBackToPlainAtomically() {
        val viewModel = CardEditViewModel(repository)
        viewModel.selectImageSource(ImageSourceType.PROVIDER)

        viewModel.selectNetwork(null)

        assertEquals(ImageSourceType.NONE, viewModel.uiState.value.imageSourceType)
        assertNull(viewModel.uiState.value.imageProviderKey)
    }

    @Test
    fun selectingProviderWithoutNetwork_defaultsToVisa() {
        val viewModel = CardEditViewModel(repository)
        viewModel.selectNetwork(null)

        viewModel.selectImageSource(ImageSourceType.PROVIDER)

        assertEquals(CardNetworkProvider.VISA.key, viewModel.uiState.value.imageProviderKey)
    }

    @Test
    fun save_preservesNetworkForPlainAndUserCards() =
        runTest {
            suspend fun save(
                source: ImageSourceType,
                name: String,
            ) = CardEditViewModel(repository).run {
                update {
                    it.copy(
                        name = name,
                        requiredCount = "6",
                        imageSourceType = source,
                        imageProviderKey = CardNetworkProvider.UNIONPAY.key,
                    )
                }
                save()
                uiState.first { it.saveResult is CardEditSaveResult.Saved }
            }

            save(ImageSourceType.NONE, "纯色银联")
            save(ImageSourceType.USER, "图片银联")

            val cards = db.cardDao().listAll().associateBy { it.name }
            assertEquals("unionpay", cards.getValue("纯色银联").imageProviderKey)
            assertEquals("unionpay", cards.getValue("图片银联").imageProviderKey)
        }

    @Test
    fun loadAndSave_preservesIndependentNetworkAndImageState() =
        runTest {
            suspend fun verifyRoundTrip(
                name: String,
                source: ImageSourceType,
                networkKey: String,
                imageUri: String?,
            ) {
                val id =
                    repository.upsertCard(
                        CardEntity(
                            name = name,
                            bank = "",
                            cardNumberMasked = "",
                            requiredCount = 6,
                            colorArgb = 0xFF0061A4.toInt(),
                            imageSourceType = source.name,
                            imageProviderKey = networkKey,
                            imageUri = imageUri,
                        ),
                    )
                val viewModel = CardEditViewModel(repository)

                viewModel.load(id)
                viewModel.uiState.first { it.editingId == id && !it.isLoading }

                assertEquals(source, viewModel.uiState.value.imageSourceType)
                assertEquals(networkKey, viewModel.uiState.value.imageProviderKey)
                assertEquals(imageUri, viewModel.uiState.value.imageUri)

                viewModel.save()
                viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }

                val saved = db.cardDao().listAll().single { it.id == id }
                assertEquals(source.name, saved.imageSourceType)
                assertEquals(networkKey, saved.imageProviderKey)
                assertEquals(imageUri, saved.imageUri)
            }

            verifyRoundTrip(
                name = "纯色银联",
                source = ImageSourceType.NONE,
                networkKey = CardNetworkProvider.UNIONPAY.key,
                imageUri = null,
            )
            verifyRoundTrip(
                name = "图片万事达",
                source = ImageSourceType.USER,
                networkKey = CardNetworkProvider.MASTERCARD.key,
                imageUri = "content://card/mastercard",
            )
            verifyRoundTrip(
                name = "未知卡组织",
                source = ImageSourceType.NONE,
                networkKey = "future-network",
                imageUri = null,
            )
        }

    @Test
    fun repeatedSaveWhileInFlight_insertsOnlyOneCard() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.update { it.copy(name = "防重复卡", requiredCount = "6") }

            viewModel.save()
            viewModel.save()
            advanceUntilIdle()
            viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }

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
            viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }
            assertEquals(1, db.cardDao().listAll().size)
        }

    @Test
    fun leapYearFebruary28_isNormalizedBeforeSave() {
        assertEquals(LocalDate.of(2028, 2, 29), normalizeAnnualDueDate(LocalDate.of(2028, 2, 28)))
    }

    @Test
    fun todayOrPastDueDate_isRejected() {
        assertEquals(
            CardEditValidation.NEXT_DUE_MUST_BE_FUTURE,
            validateNextDue(LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 1)),
        )
    }

    @Test
    fun invalidNextDueDate_keepsFormOpenAndDoesNotWrite() =
        runTest {
            val clock = Clock.fixed(Instant.parse("2027-06-01T12:00:00Z"), ZoneOffset.UTC)
            val viewModel = CardEditViewModel(repository, clock)
            viewModel.update {
                it.copy(
                    name = "日期错误卡",
                    requiredCount = "6",
                    nextDueDateMillis =
                        com.shuaji.cards.data.DateToken
                            .fromLocalDate(LocalDate.of(2027, 6, 1)),
                )
            }

            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                CardEditSaveResult.ValidationError(CardEditValidation.NEXT_DUE_MUST_BE_FUTURE),
                viewModel.uiState.value.saveResult,
            )
            assertEquals(0, db.cardDao().listAll().size)
        }
}
