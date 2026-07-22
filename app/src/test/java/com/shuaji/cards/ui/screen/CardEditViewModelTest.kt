package com.shuaji.cards.ui.screen

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.FailClosedTestUserCardImageStore
import com.shuaji.cards.data.ImageAssetId
import com.shuaji.cards.data.StagedUserImage
import com.shuaji.cards.data.UserCardImageStore
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.ui.theme.DefaultBrandPrimary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var userImages: FakeUserCardImageStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        userImages = FakeUserCardImageStore()
        repository =
            CardRepository(
                database = db,
                cardDao = db.cardDao(),
                transactionDao = db.transactionDao(),
                folderDao = db.cardFolderDao(),
                clock = Clock.systemUTC(),
                zoneIdProvider = { ZoneOffset.UTC },
                boundaryTicks = flowOf(Unit),
                userImages = userImages,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun newCard_usesDefaultBrandPrimaryColor() {
        assertEquals(DefaultBrandPrimary.toArgb(), CardEditUiState().colorArgb)
    }

    @Test
    fun newCard_keepsTypeUnspecifiedUntilUserChooses() {
        val state = CardEditUiState()

        assertEquals(CardType.UNSPECIFIED, state.cardType)
        assertEquals("", state.statementDay)
        assertEquals("", state.repaymentDay)
    }

    @Test
    fun creditMonthDays_areOptionalAndMustBeWithinCalendarRange() {
        assertTrue(isOptionalCardMonthDayValid(""))
        assertTrue(isOptionalCardMonthDayValid("1"))
        assertTrue(isOptionalCardMonthDayValid("31"))
        assertFalse(isOptionalCardMonthDayValid("0"))
        assertFalse(isOptionalCardMonthDayValid("32"))
        assertFalse(isOptionalCardMonthDayValid("invalid"))

        val invalid =
            CardEditUiState(
                name = "信用卡",
                cardType = CardType.CREDIT,
                statementDay = "32",
            )
        val ignoredForDebit = invalid.copy(cardType = CardType.DEBIT)

        assertTrue(invalid.isStatementDayInvalid)
        assertFalse(invalid.canSave)
        assertFalse(ignoredForDebit.isStatementDayInvalid)
        assertTrue(ignoredForDebit.canSave)
    }

    @Test
    fun persistedMonthDay_isCreditOnly() {
        assertEquals(12, persistedCardMonthDay(CardType.CREDIT, "12"))
        assertNull(persistedCardMonthDay(CardType.CREDIT, ""))
        assertNull(persistedCardMonthDay(CardType.DEBIT, "12"))
        assertNull(persistedCardMonthDay(CardType.UNSPECIFIED, "12"))
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
    fun loadMissingCard_entersMissingStateAndCannotSave() =
        runTest {
            val viewModel = CardEditViewModel(repository)

            viewModel.load(9_999L)
            val state = viewModel.uiState.first { it.loadState == CardEditLoadState.MISSING }

            assertFalse(state.canSave)
            assertNull(state.editingId)
        }

    @Test
    fun loadExistingUnclassifiedCard_keepsTypeUnspecifiedForUserChoice() =
        runTest {
            val id =
                repository.upsertCard(
                    CardEntity(
                        name = "历史卡",
                        bank = "",
                        cardNumberMasked = "",
                        requiredCount = 6,
                        colorArgb = 0,
                    ),
                )
            val viewModel = CardEditViewModel(repository)

            viewModel.load(id)
            val state = viewModel.uiState.first { it.loadState == CardEditLoadState.READY }

            assertEquals(CardType.UNSPECIFIED, state.cardType)
            assertEquals("", state.statementDay)
            assertEquals("", state.repaymentDay)
        }

    @Test
    fun saveAndLoadCreditCard_roundTripsTypeAndMonthDays() =
        runTest {
            val writer = CardEditViewModel(repository)
            writer.update {
                it.copy(
                    name = "信用卡",
                    cardType = CardType.CREDIT,
                    statementDay = "8",
                    repaymentDay = "26",
                )
            }

            writer.save()
            val saved = writer.uiState.first { it.saveResult is CardEditSaveResult.Saved }
            val id = requireNotNull((saved.saveResult as? CardEditSaveResult.Saved)?.id)
            val reader = CardEditViewModel(repository)
            reader.load(id)
            val loaded = reader.uiState.first { it.loadState == CardEditLoadState.READY }

            assertEquals(CardType.CREDIT, loaded.cardType)
            assertEquals("8", loaded.statementDay)
            assertEquals("26", loaded.repaymentDay)
        }

    @Test
    fun saveDebitCard_dropsStaleCreditMonthDays() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.update {
                it.copy(
                    name = "借记卡",
                    cardType = CardType.DEBIT,
                    statementDay = "8",
                    repaymentDay = "26",
                )
            }

            viewModel.save()
            viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }
            val stored = db.cardDao().listAll().single()

            assertEquals(CardType.DEBIT.key, stored.cardType)
            assertNull(stored.statementDay)
            assertNull(stored.repaymentDay)
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
    fun changingVisualStyle_preservesUnchangedOwnedImageAsset() =
        runTest {
            val assetId = "a".repeat(64)
            val id =
                repository.upsertCard(
                    CardEntity(
                        name = "保留原图",
                        bank = "",
                        cardNumberMasked = "",
                        requiredCount = 6,
                        colorArgb = 0,
                        imageAssetId = assetId,
                        imageSourceType = ImageSourceType.USER.key,
                    ),
                )
            val viewModel = CardEditViewModel(repository)
            viewModel.load(id)
            viewModel.uiState.first { it.loadState == CardEditLoadState.READY }

            viewModel.selectImageSource(ImageSourceType.NONE)
            viewModel.save()
            viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }

            val saved = requireNotNull(db.cardDao().getById(id))
            assertEquals(ImageSourceType.NONE.key, saved.imageSourceType)
            assertEquals(assetId, saved.imageAssetId)
        }

    @Test
    fun changingVisualStyleAfterSelection_stillPersistsSelectedOwnedImage() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.update { it.copy(name = "先选原图再换样式") }
            viewModel.selectUserImage("content://card/selected-before-style")
            advanceUntilIdle()
            val selected = userImages.staged.single()

            viewModel.selectImageSource(ImageSourceType.PROVIDER)
            viewModel.save()
            viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }

            val saved = db.cardDao().listAll().single()
            assertEquals(ImageSourceType.PROVIDER.key, saved.imageSourceType)
            assertEquals(selected.assetId.value, saved.imageAssetId)
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
                            imageSourceType = source.key,
                            imageProviderKey = networkKey,
                            imageUri = imageUri,
                        ),
                    )
                val viewModel = CardEditViewModel(repository)

                viewModel.load(id)
                viewModel.uiState.first { it.editingId == id && it.loadState == CardEditLoadState.READY }

                assertEquals(source, viewModel.uiState.value.imageSourceType)
                assertEquals(networkKey, viewModel.uiState.value.imageProviderKey)
                assertEquals(imageUri, viewModel.uiState.value.imageUri)

                viewModel.save()
                viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }

                val saved = db.cardDao().listAll().single { it.id == id }
                assertEquals(source.key, saved.imageSourceType)
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
    fun saveWithoutImageEdit_preservesOwnedAssetMigratedAfterFormLoad() =
        runTest {
            val legacyUri = "content://card/legacy"
            val assetId = "a".repeat(64)
            val id =
                db.cardDao().upsert(
                    CardEntity(
                        name = "迁移竞态卡",
                        bank = "",
                        cardNumberMasked = "",
                        requiredCount = 6,
                        colorArgb = 0,
                        imageSourceType = ImageSourceType.USER.key,
                        imageUri = legacyUri,
                    ),
                )
            val viewModel = CardEditViewModel(repository)
            viewModel.load(id)
            viewModel.uiState.first { it.loadState == CardEditLoadState.READY }

            assertEquals(1, db.cardDao().adoptOwnedImage(id, legacyUri, assetId))
            viewModel.update { it.copy(name = "只修改名称") }
            viewModel.save()
            viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }

            val saved = requireNotNull(db.cardDao().getById(id))
            assertEquals("只修改名称", saved.name)
            assertEquals(assetId, saved.imageAssetId)
            assertNull(saved.imageUri)
        }

    @Test
    fun explicitImageClear_overridesOwnedAssetMigratedAfterFormLoad() =
        runTest {
            val legacyUri = "content://card/legacy-clear"
            val assetId = "b".repeat(64)
            val id =
                db.cardDao().upsert(
                    CardEntity(
                        name = "明确清图",
                        bank = "",
                        cardNumberMasked = "",
                        requiredCount = 6,
                        colorArgb = 0,
                        imageSourceType = ImageSourceType.USER.key,
                        imageUri = legacyUri,
                    ),
                )
            val viewModel = CardEditViewModel(repository)
            viewModel.load(id)
            viewModel.uiState.first { it.loadState == CardEditLoadState.READY }

            assertEquals(1, db.cardDao().adoptOwnedImage(id, legacyUri, assetId))
            viewModel.clearUserImage()
            viewModel.save()
            viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }

            val saved = requireNotNull(db.cardDao().getById(id))
            assertNull(saved.imageAssetId)
            assertNull(saved.imageUri)
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
    fun selectedImageLease_isReleasedWhenEditIsDiscarded() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            val uri = "content://card/pending"

            viewModel.selectUserImage(uri)
            advanceUntilIdle()
            viewModel.closeWithoutSaving()
            assertEquals(CardEditEvent.CloseReady, viewModel.events.first())

            assertEquals(listOf(uri), userImages.stagedSources)
            assertEquals(listOf(userImages.staged.toSet()), userImages.releasedPending)
        }

    @Test
    fun selectedImageLease_isFinalizedAfterSuccessfulSave() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            val uri = "content://card/committed"
            viewModel.update { it.copy(name = "图片卡") }
            viewModel.selectUserImage(uri)
            advanceUntilIdle()

            viewModel.save()
            viewModel.uiState.first { it.saveResult is CardEditSaveResult.Saved }

            val stored = db.cardDao().listAll().single()
            assertNull(stored.imageUri)
            assertEquals(
                userImages.staged
                    .single()
                    .assetId.value,
                stored.imageAssetId,
            )
            assertEquals(listOf(userImages.staged.toSet()), userImages.releasedPending)
        }

    @Test
    fun clearingImageWhileCopyFinishesLater_keepsClearAsLatestIntent() =
        runTest {
            val copyStarted = CompletableDeferred<Unit>()
            val allowCopyToFinish = CompletableDeferred<Unit>()
            userImages.copyStarted = copyStarted
            userImages.allowCopyToFinish = allowCopyToFinish
            val viewModel = CardEditViewModel(repository)
            viewModel.selectImageSource(ImageSourceType.USER)

            viewModel.selectUserImage("content://card/slow-clear")
            copyStarted.await()
            viewModel.clearUserImage()
            allowCopyToFinish.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.imageUri)
            assertEquals(UserImageDraft.Cleared, viewModel.uiState.value.userImage)
            assertEquals(1, userImages.releasedPending.flatten().size)
        }

    @Test
    fun switchingAwayWhileImageCopyFinishesLater_doesNotSwitchBackToUserStyle() =
        runTest {
            val copyStarted = CompletableDeferred<Unit>()
            val allowCopyToFinish = CompletableDeferred<Unit>()
            userImages.copyStarted = copyStarted
            userImages.allowCopyToFinish = allowCopyToFinish
            val viewModel = CardEditViewModel(repository)
            viewModel.selectImageSource(ImageSourceType.USER)

            viewModel.selectUserImage("content://card/slow-style")
            copyStarted.await()
            viewModel.selectImageSource(ImageSourceType.NONE)
            allowCopyToFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(ImageSourceType.NONE, viewModel.uiState.value.imageSourceType)
            assertNull(viewModel.uiState.value.imageUri)
            assertEquals(1, userImages.releasedPending.flatten().size)
        }

    @Test
    fun imageCopyInProgress_disablesSaveUntilOwnedImageIsReady() =
        runTest {
            val copyStarted = CompletableDeferred<Unit>()
            val allowCopyToFinish = CompletableDeferred<Unit>()
            userImages.copyStarted = copyStarted
            userImages.allowCopyToFinish = allowCopyToFinish
            val viewModel = CardEditViewModel(repository)
            viewModel.update { it.copy(name = "等待图片") }

            viewModel.selectUserImage("content://card/slow-save")
            copyStarted.await()

            assertTrue(viewModel.uiState.value.isImportingImage)
            assertFalse(viewModel.uiState.value.canSave)
            viewModel.save()
            assertTrue(db.cardDao().listAll().isEmpty())

            allowCopyToFinish.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isImportingImage)
            assertTrue(viewModel.uiState.value.canSave)
        }

    @Test
    fun cleanupFailureAfterDatabaseCommit_doesNotMisreportSuccessfulSave() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.update { it.copy(name = "清理失败仍已保存") }
            viewModel.selectUserImage("content://card/cleanup-failure")
            advanceUntilIdle()
            userImages.garbageCollectionFailure = IllegalStateException("cleanup failed")

            viewModel.save()
            val completed = viewModel.uiState.first { it.saveResult !is CardEditSaveResult.Idle }

            assertTrue(
                "actual state=$completed",
                completed.saveResult is CardEditSaveResult.Saved,
            )
            assertEquals(
                "清理失败仍已保存",
                db
                    .cardDao()
                    .listAll()
                    .single()
                    .name,
            )
        }

    @Test
    fun repeatedInitializeAfterRecreation_preservesUnsavedFormAndPendingImage() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            val uri = "content://card/recreated"
            viewModel.initialize(null)
            viewModel.update { it.copy(name = "未保存名称", bank = "未保存银行") }
            viewModel.selectUserImage(uri)
            advanceUntilIdle()

            viewModel.initialize(null)

            assertEquals("未保存名称", viewModel.uiState.value.name)
            assertEquals("未保存银行", viewModel.uiState.value.bank)
            assertEquals(userImages.staged.single().displayUri, viewModel.uiState.value.imageUri)
            assertTrue(userImages.releasedPending.isEmpty())
        }

    @Test
    fun initializeNewCard_releasesPendingImageFromPreviousForm() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            val uri = "content://card/reset"
            viewModel.selectUserImage(uri)
            advanceUntilIdle()

            viewModel.initialize(null)
            advanceUntilIdle()

            assertEquals(CardEditUiState(), viewModel.uiState.value)
            assertEquals(listOf(userImages.staged.toSet()), userImages.releasedPending)
        }

    @Test
    fun selectingAnotherImage_releasesReplacedLeaseImmediately() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.selectUserImage("content://card/first")
            advanceUntilIdle()
            val first = userImages.staged.single()

            viewModel.selectUserImage("content://card/second")
            advanceUntilIdle()

            val selected = viewModel.uiState.value.userImage as UserImageDraft.Selected
            assertEquals(userImages.staged.last(), selected.staged)
            assertEquals(listOf(setOf(first)), userImages.releasedPending)
        }

    @Test
    fun clearingViewModel_releasesAcceptedImageLeaseSynchronously() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            val store = ViewModelStore()
            store.put("card-edit", viewModel)
            viewModel.selectUserImage("content://card/cleared-view-model")
            advanceUntilIdle()

            store.clear()

            assertEquals(listOf(userImages.staged.toSet()), userImages.releasedPending)
        }

    @Test
    fun clearingViewModelDuringSave_keepsLeaseUntilDatabaseWriteFinishes() =
        runTest {
            val writeStarted = CompletableDeferred<Unit>()
            val allowWriteToFinish = CompletableDeferred<Unit>()
            val leaseReleased = CompletableDeferred<Unit>()
            userImages.leaseReleased = leaseReleased
            val delegate = db.cardDao()
            val blockingDao =
                object : CardDao by delegate {
                    override suspend fun upsert(card: CardEntity): Long {
                        writeStarted.complete(Unit)
                        return withContext(NonCancellable) {
                            allowWriteToFinish.await()
                            delegate.upsert(card)
                        }
                    }
                }
            val blockingRepository =
                CardRepository(
                    database = db,
                    cardDao = blockingDao,
                    transactionDao = db.transactionDao(),
                    folderDao = db.cardFolderDao(),
                    clock = Clock.systemUTC(),
                    zoneIdProvider = { ZoneOffset.UTC },
                    boundaryTicks = flowOf(Unit),
                    userImages = userImages,
                )
            val viewModel = CardEditViewModel(blockingRepository)
            val store = ViewModelStore()
            store.put("saving-card-edit", viewModel)
            viewModel.update { it.copy(name = "写入中销毁") }
            viewModel.selectUserImage("content://card/saving")
            advanceUntilIdle()

            viewModel.save()
            writeStarted.await()
            store.clear()

            assertTrue(userImages.releasedPending.isEmpty())
            allowWriteToFinish.complete(Unit)
            leaseReleased.await()

            assertEquals(listOf(userImages.staged.toSet()), userImages.releasedPending)
            assertEquals(
                userImages.staged
                    .single()
                    .assetId.value,
                db
                    .cardDao()
                    .listAll()
                    .single()
                    .imageAssetId,
            )
        }

    @Test
    fun editingCardDeletedBeforeSave_doesNotRecreateIt() =
        runTest {
            val stored =
                CardEntity(
                    name = "即将删除",
                    bank = "",
                    cardNumberMasked = "",
                    requiredCount = 6,
                    colorArgb = 0xFF0061A4.toInt(),
                )
            val id = repository.upsertCard(stored)
            val viewModel = CardEditViewModel(repository)
            viewModel.load(id)
            viewModel.uiState.first { it.loadState == CardEditLoadState.READY }
            repository.deleteCard(stored.copy(id = id))

            viewModel.update { it.copy(name = "不应复活") }
            viewModel.save()
            val state = viewModel.uiState.first { it.loadState == CardEditLoadState.MISSING }

            assertEquals(CardEditLoadState.MISSING, state.loadState)
            assertTrue(db.cardDao().listAll().isEmpty())
        }

    @Test
    fun closingFirst_preventsSaveFromStarting() =
        runTest {
            val viewModel = CardEditViewModel(repository)
            viewModel.update { it.copy(name = "关闭中的卡", requiredCount = "6") }

            viewModel.closeWithoutSaving()
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

            viewModel.closeWithoutSaving()
            assertFalse(viewModel.uiState.value.isClosing)
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

    @Test
    fun dueDateValidation_usesCurrentZoneAtSaveTime() =
        runTest {
            val clock = Clock.fixed(Instant.parse("2027-06-01T23:30:00Z"), ZoneOffset.UTC)
            var zone = ZoneOffset.ofHours(-1)
            val viewModel = CardEditViewModel(repository, clock) { zone }
            viewModel.update {
                it.copy(
                    name = "切换时区",
                    requiredCount = "6",
                    nextDueDateMillis =
                        com.shuaji.cards.data.DateToken
                            .fromAnnualDate(LocalDate.of(2027, 6, 2)),
                )
            }
            zone = ZoneOffset.ofHours(1)

            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                CardEditSaveResult.ValidationError(CardEditValidation.NEXT_DUE_MUST_BE_FUTURE),
                viewModel.uiState.value.saveResult,
            )
            assertEquals(0, db.cardDao().listAll().size)
        }
}

private class FakeUserCardImageStore : UserCardImageStore by FailClosedTestUserCardImageStore {
    val stagedSources = mutableListOf<String>()
    val staged = mutableListOf<StagedUserImage>()
    val releasedPending = mutableListOf<Set<StagedUserImage>>()
    private val activeLeases = mutableSetOf<StagedUserImage>()
    var garbageCollectionCalls = 0
    var stageSucceeds = true
    var copyStarted: CompletableDeferred<Unit>? = null
    var allowCopyToFinish: CompletableDeferred<Unit>? = null
    var leaseReleased: CompletableDeferred<Unit>? = null
    var garbageCollectionFailure: Throwable? = null

    override suspend fun stageFromUri(uri: String): StagedUserImage {
        if (!stageSucceeds) error("stage failed")
        copyStarted?.complete(Unit)
        // 模拟真实存储边界已经开始阻塞 I/O：调用方取消后，底层仍可能先完成复制再交回控制权。
        allowCopyToFinish?.let { gate -> withContext(NonCancellable) { gate.await() } }
        stagedSources += uri
        val index = staged.size + 1
        val assetId = requireNotNull(ImageAssetId.parse(index.toString(16).padStart(64, '0')))
        return StagedUserImage(assetId, "lease-$index", "file:///owned/${assetId.value}").also {
            staged += it
            activeLeases += it
        }
    }

    override fun releaseLeases(images: Set<StagedUserImage>) {
        val released = images.filter(activeLeases::remove).toSet()
        if (released.isNotEmpty()) {
            releasedPending += released
            leaseReleased?.complete(Unit)
        }
    }

    override suspend fun collectGarbage() {
        garbageCollectionCalls++
        garbageCollectionFailure?.let { throw it }
    }

    override fun resolve(assetId: ImageAssetId): String = "file:///owned/${assetId.value}"
}
