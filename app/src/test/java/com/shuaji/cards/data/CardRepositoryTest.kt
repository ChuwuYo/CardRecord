package com.shuaji.cards.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** [CardRepository] 的 Room 数据流、统计窗口与事务写入回归测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CardRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2027-06-01T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var repo: CardRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo =
            CardRepository(
                database = db,
                cardDao = db.cardDao(),
                transactionDao = db.transactionDao(),
                folderDao = db.cardFolderDao(),
                clock = clock,
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
    fun recordSwipe_unscheduledCardIncrementsDerivedCount() =
        runBlocking {
            val id = insertCard(due = null)

            assertTrue(repo.recordSwipe(id) is SwipeRecordResult.Recorded)
            assertTrue(repo.recordSwipe(id) is SwipeRecordResult.Recorded)

            assertEquals(2, currentCount(id))
        }

    @Test
    fun transactionInsert_duplicateIdFailsWithoutReplacingOriginal() =
        runBlocking {
            val cardId = insertCard(due = null)
            db.transactionDao().insert(TransactionEntity(id = 7L, cardId = cardId, occurredAtMillis = 100L))

            val failure =
                runCatching {
                    db.transactionDao().insert(TransactionEntity(id = 7L, cardId = cardId, occurredAtMillis = 200L))
                }.exceptionOrNull()

            assertTrue(failure is android.database.sqlite.SQLiteConstraintException)
            assertEquals(
                100L,
                db
                    .transactionDao()
                    .listAll()
                    .single()
                    .occurredAtMillis,
            )
        }

    @Test
    fun upsert_existingCardPreservesTransactions() =
        runBlocking {
            val id = insertCard(due = null, name = "修改前")
            repo.recordSwipe(id)
            repo.recordSwipe(id)

            val original = requireNotNull(db.cardDao().getById(id))
            repo.upsertCard(
                original.copy(
                    name = "修改后",
                    colorArgb = 0xFF2E7D32.toInt(),
                ),
            )

            val updated = requireNotNull(repo.observeCardDetails(id).first()).card
            assertEquals("修改后", updated.card.name)
            assertEquals(0xFF2E7D32.toInt(), updated.card.colorArgb)
            assertEquals("编辑卡片不能删除已有流水", 2, updated.currentCount)
        }

    @Test
    fun cardWriteBoundary_keepsCreditDaysOnlyForCreditCards() =
        runBlocking {
            val id =
                repo.upsertCard(
                    CardEntity(
                        name = "借记卡",
                        bank = "某银行",
                        cardNumberMasked = "**** 1234",
                        cardType = CardType.DEBIT.key,
                        statementDay = 8,
                        repaymentDay = 26,
                        requiredCount = 6,
                        colorArgb = 0,
                    ),
                )
            val debit = requireNotNull(db.cardDao().getById(id))
            assertNull(debit.statementDay)
            assertNull(debit.repaymentDay)

            assertTrue(
                repo.updateCard(
                    debit.copy(
                        cardType = CardType.CREDIT.key,
                        statementDay = 8,
                        repaymentDay = 26,
                    ),
                ),
            )
            val credit = requireNotNull(db.cardDao().getById(id))
            assertEquals(8, credit.statementDay)
            assertEquals(26, credit.repaymentDay)
        }

    @Test
    fun cardWriteBoundary_rejectsInvalidCreditDayBeforeDatabaseWrite() =
        runBlocking {
            val failure =
                try {
                    repo.upsertCard(
                        CardEntity(
                            name = "异常信用卡",
                            bank = "某银行",
                            cardNumberMasked = "**** 1234",
                            cardType = CardType.CREDIT.key,
                            statementDay = 32,
                            requiredCount = 6,
                            colorArgb = 0,
                        ),
                    )
                    null
                } catch (error: IllegalArgumentException) {
                    error
                }

            assertTrue(failure != null)
            assertTrue(db.cardDao().listAll().isEmpty())
        }

    @Test
    fun insertFolder_existingIdFailsWithoutDetachingAssignedCard() =
        runBlocking {
            val folderId =
                repo.insertFolder(
                    CardFolderEntity(name = "日常", colorArgb = 0xFF1565C0.toInt()),
                )
            val cardId = insertCard(due = null, folderId = folderId)

            val failure =
                runCatching {
                    repo.insertFolder(
                        CardFolderEntity(
                            id = folderId,
                            name = "冲突文件夹",
                            colorArgb = 0xFF2E7D32.toInt(),
                        ),
                    )
                }.exceptionOrNull()

            assertTrue("重复主键应让只插入操作失败", failure is android.database.sqlite.SQLiteConstraintException)
            assertEquals(folderId, db.cardDao().getById(cardId)?.folderId)
            assertEquals(
                "日常",
                db
                    .cardFolderDao()
                    .listAll()
                    .single { it.id == folderId }
                    .name,
            )
        }

    @Test
    fun updateFolder_existingFolderPreservesAssignedCard() =
        runBlocking {
            val folderId =
                repo.insertFolder(
                    CardFolderEntity(name = "修改前", colorArgb = 0xFF1565C0.toInt()),
                )
            val cardId = insertCard(due = null, folderId = folderId)
            val original = db.cardFolderDao().listAll().single { it.id == folderId }

            assertTrue(repo.updateFolder(original.copy(name = "修改后", colorArgb = 0xFF2E7D32.toInt())))

            assertEquals(folderId, db.cardDao().getById(cardId)?.folderId)
            assertEquals(
                "修改后",
                db
                    .cardFolderDao()
                    .listAll()
                    .single { it.id == folderId }
                    .name,
            )
        }

    @Test
    fun staleFolderAndTransactionWrites_reportNoAffectedRow() =
        runBlocking {
            val missingFolder = CardFolderEntity(id = 9_999L, name = "不存在", colorArgb = 0)

            assertFalse(repo.updateFolder(missingFolder))
            assertFalse(repo.deleteFolder(missingFolder))
            assertFalse(repo.deleteTransaction(9_999L))
        }

    @Test
    fun observeFoldersWithCardCounts_derivesCountsInOneReactiveQuery() =
        runBlocking {
            val folderId = repo.insertFolder(CardFolderEntity(name = "日常", colorArgb = 0xFF1565C0.toInt()))
            val cardId = insertCard(due = null, folderId = folderId)

            assertEquals(
                1,
                repo
                    .observeFoldersWithCardCounts()
                    .first()
                    .single()
                    .cardCount,
            )

            val card = requireNotNull(db.cardDao().getById(cardId))
            repo.upsertCard(card.copy(folderId = null))

            assertEquals(
                0,
                repo
                    .observeFoldersWithCardCounts()
                    .first()
                    .single()
                    .cardCount,
            )
        }

    @Test
    fun recordSwipe_missingCardReturnsExplicitResult() =
        runBlocking {
            assertEquals(SwipeRecordResult.CardMissing, repo.recordSwipe(cardId = 999L))
        }

    @Test
    fun upcomingCard_excludesOldTransactionsAndRejectsRecord() =
        runBlocking {
            val id = insertCard(due = "2028-06-02")
            insertTransaction(id, "2026-07-01T00:00:00Z")

            assertEquals(0, currentCount(id))
            val result = repo.recordSwipe(id)
            assertTrue(result is SwipeRecordResult.CountingNotStarted)
            assertEquals(LocalDate.of(2027, 6, 2), (result as SwipeRecordResult.CountingNotStarted).startDate)
            assertEquals(1, db.transactionDao().listAll().size)
        }

    @Test
    fun activeCard_countsOnlyHalfOpenWindow() =
        runBlocking {
            val id = insertCard(due = "2028-06-01")
            insertTransaction(id, "2027-05-31T23:59:59.999Z")
            insertTransaction(id, "2027-06-01T00:00:00Z")
            insertTransaction(id, "2028-05-31T23:59:59.999Z")
            insertTransaction(id, "2028-06-01T00:00:00Z")

            assertEquals(2, currentCount(id))
        }

    @Test
    fun unscheduledCard_countsAllTransactions() =
        runBlocking {
            val id = insertCard(due = null)
            insertTransaction(id, "2020-01-01T00:00:00Z")

            assertEquals(1, currentCount(id))
        }

    @Test
    fun resetUnscheduledCard_deletesAllCardTransactions() =
        runBlocking {
            val id = insertCard(due = null)
            insertTransaction(id, "2020-01-01T00:00:00Z")
            insertTransaction(id, "2027-01-01T00:00:00Z")

            assertTrue(repo.resetCardCycle(id))
            assertEquals(emptyList<String>(), storedInstants(id))
        }

    @Test
    fun overdueNormalization_advancesDateWithoutDeletingHistory() =
        runBlocking {
            val id = insertCard(due = "2024-06-01")
            insertTransaction(id, "2024-01-01T00:00:00Z")

            assertEquals(1, repo.normalizeOverdueCycles())
            assertEquals(1, db.transactionDao().listAll().size)
            assertEquals(
                LocalDate.of(2028, 6, 1),
                storedDueDate(id),
            )
        }

    @Test
    fun overdueNormalization_ignoresFutureAndUnscheduledCards() =
        runBlocking {
            insertCard(due = "2028-06-01")
            insertCard(due = null)

            assertEquals(0, repo.normalizeOverdueCycles())
        }

    @Test
    fun resetScheduledCard_deletesOnlyCurrentWindow() =
        runBlocking {
            val id = insertCard(due = "2028-06-01")
            insertTransaction(id, "2026-01-01T00:00:00Z")
            insertTransaction(id, "2027-07-01T00:00:00Z")

            assertTrue(repo.resetCardCycle(id))
            assertEquals(listOf("2026-01-01T00:00:00Z"), storedInstants(id))
        }

    @Test
    fun resetUpcomingCard_isRejectedWithoutDeletingHistory() =
        runBlocking {
            val id = insertCard(due = "2029-06-01")
            insertTransaction(id, "2026-01-01T00:00:00Z")

            assertFalse(repo.resetCardCycle(id))
            assertEquals(listOf("2026-01-01T00:00:00Z"), storedInstants(id))
        }

    @Test
    fun resetMissingCard_returnsFalse() =
        runBlocking {
            assertFalse(repo.resetCardCycle(999L))
        }

    @Test
    fun recordSwipe_normalizesOverdueCycleInSameTransaction() =
        runBlocking {
            val id = insertCard(due = "2024-06-01")

            val result = repo.recordSwipe(id)

            assertTrue(result is SwipeRecordResult.Recorded)
            assertEquals(1, currentCount(id))
            assertEquals(
                LocalDate.of(2028, 6, 1),
                storedDueDate(id),
            )
        }

    @Test
    fun recordSwipe_crossingDueBoundaryUsesOneTimeSnapshot() =
        runBlocking {
            val boundaryClock = SteppingClock(Instant.parse("2028-05-31T23:59:59.999Z"))
            repo = createRepository(boundaryClock)
            val id = insertCard(due = "2028-06-01")

            assertTrue(repo.recordSwipe(id) is SwipeRecordResult.Recorded)

            val stored = db.transactionDao().listAll().single()
            assertEquals(Instant.parse("2028-05-31T23:59:59.999Z").toEpochMilli(), stored.occurredAtMillis)
            assertEquals(1, boundaryClock.instantCalls)
        }

    @Test
    fun resetCardCycle_crossingDueBoundaryUsesOneTimeSnapshot() =
        runBlocking {
            val boundaryClock = SteppingClock(Instant.parse("2028-05-31T23:59:59.999Z"))
            repo = createRepository(boundaryClock)
            val id = insertCard(due = "2028-06-01")
            insertTransaction(id, "2027-07-01T00:00:00Z")

            assertTrue(repo.resetCardCycle(id))

            assertEquals(emptyList<String>(), storedInstants(id))
            assertEquals(1, boundaryClock.instantCalls)
        }

    @Test
    fun normalizeOverdueCycles_crossingDueBoundaryDoesNotSplitIdenticalCards() =
        runBlocking {
            val boundaryClock = SteppingClock(Instant.parse("2028-05-31T23:59:59.999Z"))
            repo = createRepository(boundaryClock)
            insertCard(due = "2028-06-01", name = "A")
            insertCard(due = "2028-06-01", name = "B")

            assertEquals(0, repo.normalizeOverdueCycles())

            assertEquals(
                listOf(LocalDate.of(2028, 6, 1), LocalDate.of(2028, 6, 1)),
                db.cardDao().listAll().map { DateToken.toLocalDate(requireNotNull(it.nextDueDateMillis)) },
            )
            assertEquals(1, boundaryClock.instantCalls)
        }

    @Test
    fun observeCardDetails_usesDerivedCountAndKeepsSortedFullHistory() =
        runBlocking {
            val id = insertCard(due = "2028-06-01")
            insertTransaction(id, "2026-01-01T00:00:00Z")
            insertTransaction(id, "2027-07-01T00:00:00Z")

            val details = requireNotNull(repo.observeCardDetails(id).first())

            assertEquals(1, details.card.currentCount)
            assertEquals(
                listOf("2027-07-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                details.swipes.map { Instant.ofEpochMilli(it.occurredAtMillis).toString() },
            )
        }

    private suspend fun insertCard(
        due: String?,
        name: String = "Visa",
        folderId: Long? = null,
    ): Long =
        repo.upsertCard(
            CardEntity(
                name = name,
                bank = "某银行",
                cardNumberMasked = "**** 1234",
                nextDueDateMillis = due?.let { DateToken.fromLocalDate(LocalDate.parse(it)) },
                requiredCount = 6,
                colorArgb = 0xFF1234,
                folderId = folderId,
            ),
        )

    private suspend fun insertTransaction(
        cardId: Long,
        instant: String,
    ) {
        db.transactionDao().insert(
            TransactionEntity(cardId = cardId, occurredAtMillis = Instant.parse(instant).toEpochMilli()),
        )
    }

    private suspend fun storedInstants(cardId: Long): List<String> =
        db
            .transactionDao()
            .listAll()
            .filter { it.cardId == cardId }
            .sortedBy(TransactionEntity::occurredAtMillis)
            .map { Instant.ofEpochMilli(it.occurredAtMillis).toString() }

    private suspend fun currentCount(cardId: Long): Int =
        repo
            .observeCards()
            .first()
            .single { it.card.id == cardId }
            .currentCount

    private suspend fun storedDueDate(cardId: Long): LocalDate {
        val card = requireNotNull(db.cardDao().getById(cardId))
        return DateToken.toLocalDate(requireNotNull(card.nextDueDateMillis))
    }

    private fun createRepository(clock: Clock): CardRepository =
        CardRepository(
            database = db,
            cardDao = db.cardDao(),
            transactionDao = db.transactionDao(),
            folderDao = db.cardFolderDao(),
            clock = clock,
            zoneIdProvider = { ZoneOffset.UTC },
            boundaryTicks = flowOf(Unit),
            userImages = FailClosedTestUserCardImageStore,
        )

    private class SteppingClock(
        private val first: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        var instantCalls: Int = 0
            private set

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = SteppingClock(first, zone)

        override fun instant(): Instant = first.plusMillis(instantCalls++.toLong())
    }
}
