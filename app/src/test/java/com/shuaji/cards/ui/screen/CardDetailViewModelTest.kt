package com.shuaji.cards.ui.screen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.FailClosedTestUserCardImageStore
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
class CardDetailViewModelTest {
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
                clock = Clock.fixed(Instant.parse("2027-07-01T00:00:00Z"), ZoneOffset.UTC),
                zoneIdProvider = { ZoneOffset.UTC },
                boundaryTicks = flowOf(Unit),
                userImages = FailClosedTestUserCardImageStore,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun missingCard_isDistinctFromLoading() =
        runTest {
            val viewModel = CardDetailViewModel(repository, cardId = 404)

            assertTrue(viewModel.uiState.value is CardDetailUiState.Loading)
            assertTrue(viewModel.uiState.first { it !is CardDetailUiState.Loading } is CardDetailUiState.Missing)
        }

    @Test
    fun existingCard_becomesLoaded() =
        runTest {
            val id = repository.upsertCard(card())
            val viewModel = CardDetailViewModel(repository, id)

            val state = viewModel.uiState.first { it is CardDetailUiState.Loaded }

            assertTrue((state as CardDetailUiState.Loaded).detail.card.id == id)
        }

    @Test
    fun deleteCard_emitsSuccessOnlyAfterRepositoryDeletionCompletes() =
        runTest {
            val id = repository.upsertCard(card())
            val viewModel = CardDetailViewModel(repository, id)
            viewModel.uiState.first { it is CardDetailUiState.Loaded }
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.deleteCard()

            assertTrue(event.await() is CardDetailEvent.Deleted)
            assertTrue(db.cardDao().getById(id) == null)
        }

    @Test
    fun deleteCard_failureEmitsFailureAndDoesNotPretendSuccess() =
        runTest {
            val id = repository.upsertCard(card())
            val viewModel = CardDetailViewModel(repository, id)
            viewModel.uiState.first { it is CardDetailUiState.Loaded }
            val stored = requireNotNull(db.cardDao().getById(id))
            db.cardDao().delete(stored)
            val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.events.first() }

            viewModel.deleteCard()

            assertTrue(event.await() is CardDetailEvent.DeleteFailed)
        }

    @Test
    fun detailSnapshot_marksOnlyWindowRowsAsCurrent() {
        val start = LocalDate.of(2027, 6, 1)
        val ui =
            CardDetailUi(
                card = card(),
                currentCount = 1,
                isExpired = false,
                lastSwipeAtMillis = null,
                cycle =
                    AnnualFeeCycle.Active(
                        startBoundaryMillis = Instant.parse("2027-06-01T00:00:00Z").toEpochMilli(),
                        dueBoundaryMillis = Instant.parse("2028-06-01T00:00:00Z").toEpochMilli(),
                    ),
            )

        assertTrue(ui.isCurrentPeriod(transaction("2027-07-01T00:00:00Z")))
        assertFalse(ui.isCurrentPeriod(transaction("2026-07-01T00:00:00Z")))
    }

    @Test
    fun detailCreditDays_areExposedOnlyForCreditCardsWithValidValues() {
        fun detail(card: CardEntity) =
            CardDetailUi(
                card = card,
                currentCount = 0,
                isExpired = false,
                lastSwipeAtMillis = null,
                cycle = AnnualFeeCycle.Overdue,
            )

        val credit =
            detail(
                card().copy(
                    cardType = CardType.CREDIT.key,
                    statementDay = 8,
                    repaymentDay = 26,
                ),
            )
        val debitWithStaleValues =
            detail(
                card().copy(
                    cardType = CardType.DEBIT.key,
                    statementDay = 8,
                    repaymentDay = 26,
                ),
            )
        val creditWithInvalidValues =
            detail(
                card().copy(
                    cardType = CardType.CREDIT.key,
                    statementDay = 0,
                    repaymentDay = 32,
                ),
            )
        val unspecifiedWithStaleValues =
            detail(
                card().copy(
                    statementDay = 8,
                    repaymentDay = 26,
                ),
            )

        assertTrue(credit.hasDetailInfo)
        assertEquals(CardType.CREDIT, credit.selectedCardType)
        assertEquals(8, credit.statementDay)
        assertEquals(26, credit.repaymentDay)
        assertTrue(debitWithStaleValues.hasDetailInfo)
        assertEquals(CardType.DEBIT, debitWithStaleValues.selectedCardType)
        assertNull(debitWithStaleValues.statementDay)
        assertTrue(creditWithInvalidValues.hasDetailInfo)
        assertNull(creditWithInvalidValues.repaymentDay)
        assertFalse(unspecifiedWithStaleValues.hasDetailInfo)
        assertNull(unspecifiedWithStaleValues.selectedCardType)
    }

    private fun card() =
        CardEntity(
            name = "卡",
            bank = "",
            cardNumberMasked = "",
            requiredCount = 5,
            colorArgb = 0,
        )

    private fun transaction(instant: String) =
        TransactionEntity(
            cardId = 1,
            occurredAtMillis = Instant.parse(instant).toEpochMilli(),
        )
}
