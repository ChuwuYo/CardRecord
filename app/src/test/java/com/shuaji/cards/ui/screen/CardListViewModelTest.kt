package com.shuaji.cards.ui.screen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.MainDispatcherRule
import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardEntity
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
class CardListViewModelTest {
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
    fun requestDelete_waitsForConfirmation() =
        runTest {
            val card = createCardWithTwoTransactions()
            val viewModel = CardListViewModel(repository)

            viewModel.requestDelete(card)
            advanceUntilIdle()

            assertNotNull(db.cardDao().getById(card.card.id))
            assertEquals(2, db.transactionDao().listAll().size)
            assertEquals(
                card.card.id,
                viewModel.pendingDelete.value
                    ?.card
                    ?.id,
            )
        }

    @Test
    fun cancelDelete_preservesCardAndTransactions() =
        runTest {
            val card = createCardWithTwoTransactions()
            val viewModel = CardListViewModel(repository)
            viewModel.requestDelete(card)

            viewModel.cancelDelete()
            advanceUntilIdle()

            assertNotNull(db.cardDao().getById(card.card.id))
            assertEquals(2, db.transactionDao().listAll().size)
            assertNull(viewModel.pendingDelete.value)
        }

    @Test
    fun confirmDelete_removesCardAndTransactions() =
        runTest {
            val card = createCardWithTwoTransactions()
            val viewModel = CardListViewModel(repository)
            viewModel.requestDelete(card)

            viewModel.confirmDelete()
            db.cardDao().observeById(card.card.id).first { it == null }
            db.transactionDao().observeAll().first { it.isEmpty() }

            assertNull(db.cardDao().getById(card.card.id))
            assertEquals(0, db.transactionDao().listAll().size)
            assertNull(viewModel.pendingDelete.value)
        }

    private suspend fun createCardWithTwoTransactions(): CardUi {
        val card =
            CardEntity(
                name = "待删除",
                bank = "某银行",
                cardNumberMasked = "**** 1234",
                requiredCount = 6,
                colorArgb = 0xFF0061A4.toInt(),
            )
        val id = repository.upsertCard(card)
        repository.recordSwipe(id)
        repository.recordSwipe(id)
        return CardUi(
            card = card.copy(id = id),
            currentCount = 2,
            isExpired = false,
            lastSwipeAtMillis = null,
            cycle = AnnualFeeCycle.resolve(null, Clock.systemUTC().instant(), ZoneOffset.UTC),
        )
    }
}
