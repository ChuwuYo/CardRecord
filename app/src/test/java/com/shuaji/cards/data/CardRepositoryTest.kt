package com.shuaji.cards.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * [CardRepository] 核心业务流测试：刷卡计数（派生 currentCount）、重置、年费自动续期。
 *
 * 用 Robolectric + 内存 Room 跑 JVM 单测。重点验证：
 * - currentCount 由流水 COUNT 实时算（不存冗余字段）
 * - recordSwipe 对不存在的卡返回 null（外键防护）
 * - resetOverdueCycles 的「按整年推进 + 清流水」语义，含多年累积与边界
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CardRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: CardRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = CardRepository(db.cardDao(), db.transactionDao(), db.cardFolderDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleCard(
        name: String = "Visa",
        nextDue: Long? = null,
    ): CardEntity =
        CardEntity(
            name = name,
            bank = "某银行",
            cardNumberMasked = "**** 1234",
            nextDueDateMillis = nextDue,
            requiredCount = 6,
            colorArgb = 0xFF1234,
        )

    private suspend fun currentCount(cardId: Long): Int =
        repo
            .observeCards()
            .first()
            .single { it.card.id == cardId }
            .currentCount

    @Test
    fun recordSwipe_incrementsDerivedCount() =
        runBlocking {
            val id = repo.upsertCard(sampleCard())
            assertEquals(0, currentCount(id))

            assertTrue(repo.recordSwipe(id) != null)
            assertTrue(repo.recordSwipe(id) != null)

            assertEquals(2, currentCount(id))
        }

    @Test
    fun recordSwipe_onMissingCard_returnsNull() =
        runBlocking {
            assertNull(repo.recordSwipe(cardId = 999L))
        }

    @Test
    fun resetCardCycle_clearsCount() =
        runBlocking {
            val id = repo.upsertCard(sampleCard())
            repo.recordSwipe(id)
            repo.recordSwipe(id)
            assertEquals(2, currentCount(id))

            repo.resetCardCycle(id)

            assertEquals(0, currentCount(id))
        }

    @Test
    fun resetOverdueCycles_advancesByWholeYears_andClearsTransactions() =
        runBlocking {
            val now = System.currentTimeMillis()
            // 设一个「约 2.2 年前」的结算日：续期应按整年推进到刚好 > now
            val overdueDue =
                GregorianCalendar()
                    .apply {
                        timeInMillis = now
                        add(Calendar.DAY_OF_YEAR, -800)
                    }.timeInMillis
            val id = repo.upsertCard(sampleCard(nextDue = overdueDue))
            repo.recordSwipe(id)
            repo.recordSwipe(id)
            assertEquals(2, currentCount(id))

            val resetCount = repo.resetOverdueCycles(now)

            assertEquals("应有 1 张卡被续期", 1, resetCount)
            assertEquals("续期应清空该卡流水", 0, currentCount(id))

            val newDue =
                repo
                    .observeCard(id)
                    .first()!!
                    .card.nextDueDateMillis!!
            val oneYearMillis = 366L * 24 * 60 * 60 * 1000
            assertTrue("新结算日应推到未来", newDue > now)
            assertTrue("应按整年最小推进、不过冲超过一年", newDue - now <= oneYearMillis)
        }

    @Test
    fun resetOverdueCycles_ignoresFutureAndNullDueDates() =
        runBlocking {
            val now = System.currentTimeMillis()
            val futureDue = now + 30L * 24 * 60 * 60 * 1000
            repo.upsertCard(sampleCard(name = "未来", nextDue = futureDue))
            repo.upsertCard(sampleCard(name = "无结算日", nextDue = null))

            assertEquals(0, repo.resetOverdueCycles(now))
        }
}
