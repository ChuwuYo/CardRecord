package com.shuaji.cards.data

import androidx.room.withTransaction
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardWithCount
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.data.local.TransactionDao
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

sealed interface SwipeRecordResult {
    data class Recorded(
        val transactionId: Long,
    ) : SwipeRecordResult

    data object CardMissing : SwipeRecordResult

    data class CountingNotStarted(
        val startDate: LocalDate,
    ) : SwipeRecordResult
}

data class CardDetailsSnapshot(
    val card: CardWithCount,
    val swipes: List<TransactionEntity>,
)

/** 仓库层：封装 DAO 访问、周期派生和跨表事务规则。 */
class CardRepository(
    private val database: AppDatabase,
    private val cardDao: CardDao,
    private val transactionDao: TransactionDao,
    private val folderDao: CardFolderDao,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    boundaryTicks: Flow<Unit>,
) {
    /**
     * 每个活跃 Repository 订阅固定观察 cards 与 transactions 两条查询。
     * 这里保持冷流即可；当前本地数据量不需要做应用级共享。
     */
    private val derivedSnapshots: Flow<DerivedSnapshot> =
        combine(
            cardDao.observeAll(),
            transactionDao.observeAll(),
            boundaryTicks.onStart { emit(Unit) },
        ) { cards, transactions, _ ->
            DerivedSnapshot(
                cards = deriveCards(cards, transactions),
                transactions = transactions,
            )
        }

    fun observeCards(): Flow<List<CardWithCount>> = derivedSnapshots.map { it.cards }

    /** 编辑页兼容入口；同样从统一派生快照读取。 */
    fun observeCard(id: Long): Flow<CardWithCount?> = derivedSnapshots.map { it.card(id) }

    /** 详情卡片、有效计数与完整历史来自同一次派生快照。 */
    fun observeCardDetails(id: Long): Flow<CardDetailsSnapshot?> =
        derivedSnapshots.map { snapshot ->
            val card = snapshot.cards.firstOrNull { it.card.id == id } ?: return@map null
            CardDetailsSnapshot(
                card = card,
                swipes =
                    snapshot.transactions
                        .asSequence()
                        .filter { it.cardId == id }
                        .sortedByDescending(TransactionEntity::occurredAtMillis)
                        .toList(),
            )
        }

    suspend fun upsertCard(card: CardEntity): Long = cardDao.upsert(card)

    suspend fun deleteCard(card: CardEntity) = cardDao.delete(card)

    /** 当前数据库中仍被用户卡面引用的持久化 URI。 */
    suspend fun referencedUserImageUris(): Set<String> =
        cardDao
            .listAll()
            .asSequence()
            .filter { it.imageSourceType == ImageSourceType.USER.name }
            .mapNotNull { it.imageUri }
            .toSet()

    /** 在同一 Room 写事务中校验卡片、归一化周期并记录流水。 */
    suspend fun recordSwipe(cardId: Long): SwipeRecordResult =
        database.withTransaction {
            val storedCard = cardDao.getById(cardId) ?: return@withTransaction SwipeRecordResult.CardMissing
            val card = normalizeCardIfOverdue(storedCard)
            val cycle = resolveCycle(card)
            if (cycle.state == AnnualFeeCycleState.UPCOMING) {
                return@withTransaction SwipeRecordResult.CountingNotStarted(cycle.startDate!!)
            }
            val transactionId =
                transactionDao.insert(
                    TransactionEntity(cardId = card.id, occurredAtMillis = clock.millis()),
                )
            SwipeRecordResult.Recorded(transactionId)
        }

    /**
     * 手动重置只删除当前有效窗口；无日程卡保持兼容，全删该卡流水。
     * 待开始或卡片不存在时不执行破坏性写入。
     */
    suspend fun resetCardCycle(cardId: Long): Boolean =
        database.withTransaction {
            val storedCard = cardDao.getById(cardId) ?: return@withTransaction false
            val card = normalizeCardIfOverdue(storedCard)
            val cycle = resolveCycle(card)
            when (cycle.state) {
                AnnualFeeCycleState.UNSCHEDULED -> transactionDao.deleteAllForCard(cardId)
                AnnualFeeCycleState.ACTIVE ->
                    transactionDao.deleteForCardInRange(
                        cardId = cardId,
                        start = cycle.startBoundaryMillis!!,
                        end = cycle.dueBoundaryMillis!!,
                    )
                AnnualFeeCycleState.UPCOMING -> return@withTransaction false
                AnnualFeeCycleState.OVERDUE -> error("过期周期归一化后仍为 OVERDUE")
            }
            true
        }

    suspend fun deleteTransaction(id: Long) {
        transactionDao.deleteById(id)
    }

    fun observeFolders(): Flow<List<CardFolderEntity>> = folderDao.observeAll()

    suspend fun upsertFolder(folder: CardFolderEntity): Long = folderDao.upsert(folder)

    suspend fun updateFolder(folder: CardFolderEntity) = folderDao.update(folder)

    suspend fun deleteFolder(folder: CardFolderEntity) {
        // 外键 `ON DELETE SET NULL` 会把该文件夹下的卡片归入「未分类」。
        folderDao.delete(folder)
    }

    suspend fun countCardsInFolder(folderId: Long): Int = folderDao.countCardsInFolder(folderId)

    /** 事务入口：只推进过期日期，绝不删除历史流水。 */
    suspend fun normalizeOverdueCycles(): Int =
        database.withTransaction {
            normalizeOverdueCyclesInTransaction()
        }

    /** 调用方必须已经持有 Room 写事务，供导入流程复用且避免嵌套事务。 */
    internal suspend fun normalizeOverdueCyclesInTransaction(): Int {
        var normalizedCount = 0
        cardDao.listAll().forEach { card ->
            if (resolveCycle(card).state == AnnualFeeCycleState.OVERDUE) {
                normalizeCardIfOverdue(card)
                normalizedCount += 1
            }
        }
        return normalizedCount
    }

    private suspend fun normalizeCardIfOverdue(card: CardEntity): CardEntity {
        val dueToken = card.nextDueDateMillis ?: return card
        if (resolveCycle(card).state != AnnualFeeCycleState.OVERDUE) return card
        val normalized =
            card.copy(
                nextDueDateMillis =
                    AnnualFeeCycle.advanceDueDateUntilFuture(
                        nextDueDateToken = dueToken,
                        now = clock.instant(),
                        zoneId = zoneIdProvider(),
                    ),
            )
        cardDao.update(normalized)
        return normalized
    }

    private fun resolveCycle(card: CardEntity): AnnualFeeCycle =
        AnnualFeeCycle.resolve(
            nextDueDateToken = card.nextDueDateMillis,
            now = clock.instant(),
            zoneId = zoneIdProvider(),
        )

    private fun deriveCards(
        cards: List<CardEntity>,
        transactions: List<TransactionEntity>,
    ): List<CardWithCount> {
        val byCard = transactions.groupBy(TransactionEntity::cardId)
        val now = clock.instant()
        val zone = zoneIdProvider()
        return cards.map { card ->
            val rows = byCard[card.id].orEmpty()
            val cycle = AnnualFeeCycle.resolve(card.nextDueDateMillis, now, zone)
            CardWithCount(
                card = card,
                currentCount = rows.count { cycle.includes(it.occurredAtMillis) },
                lastSwipeAtMillis = rows.maxOfOrNull(TransactionEntity::occurredAtMillis),
                cycle = cycle,
            )
        }
    }

    private data class DerivedSnapshot(
        val cards: List<CardWithCount>,
        val transactions: List<TransactionEntity>,
    ) {
        fun card(id: Long): CardWithCount? = cards.firstOrNull { it.card.id == id }
    }
}
