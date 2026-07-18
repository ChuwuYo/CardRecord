package com.shuaji.cards.data

import androidx.room.withTransaction
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardWithCount
import com.shuaji.cards.data.local.FolderWithCardCount
import com.shuaji.cards.data.local.TransactionDao
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
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
    private val imagePermissions: UserImagePermissionStore = NoOpUserImagePermissionStore,
) {
    /**
     * 每个活跃 Repository 订阅固定观察 cards 与 transactions 两条查询。
     * 这里保持冷流即可；当前本地数据量不需要做应用级共享。
     */
    private val derivedSnapshots: Flow<DerivedSnapshot> =
        combine(
            cardDao.observeAll(),
            transactionDao.observeAll(),
            boundaryTicks,
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
                        .sortedByDescending { it.occurredAtMillis }
                        .toList(),
            )
        }

    suspend fun upsertCard(card: CardEntity): Long =
        cardDao.upsert(card).also {
            imagePermissions.reconcile()
        }

    /** 编辑只允许更新仍存在的卡，返回 false 时调用方不得用 upsert 把已删除卡片复活。 */
    suspend fun updateCard(card: CardEntity): Boolean =
        (cardDao.update(card) == 1).also {
            imagePermissions.reconcile()
        }

    /** 只在确实删除一行时返回 true，防止调用方把陈旧状态误报为成功。 */
    suspend fun deleteCard(card: CardEntity): Boolean =
        (cardDao.delete(card) == 1).also {
            imagePermissions.reconcile()
        }

    suspend fun acquireUserImagePermission(uri: String): Boolean = imagePermissions.acquire(uri)

    /** 表单保存或放弃后结束临时租约；是否真正释放由全库引用集合决定。 */
    suspend fun releasePendingUserImagePermissions(uris: Set<String>) {
        if (uris.isEmpty()) return
        imagePermissions.releasePending(uris)
    }

    /** 在同一 Room 写事务中校验卡片、归一化周期并记录流水。 */
    suspend fun recordSwipe(cardId: Long): SwipeRecordResult =
        database.withTransaction {
            val now = clock.instant()
            val zone = zoneIdProvider()
            val storedCard = cardDao.getById(cardId) ?: return@withTransaction SwipeRecordResult.CardMissing
            val card = normalizeCardIfOverdue(storedCard, now, zone)
            val cycle = resolveCycle(card, now, zone)
            if (cycle is AnnualFeeCycle.Upcoming) {
                return@withTransaction SwipeRecordResult.CountingNotStarted(cycle.startDate)
            }
            check(cycle !is AnnualFeeCycle.Overdue) { "过期周期归一化后仍不可记录" }
            val transactionId =
                transactionDao.insert(
                    TransactionEntity(cardId = card.id, occurredAtMillis = now.toEpochMilli()),
                )
            SwipeRecordResult.Recorded(transactionId)
        }

    /**
     * 手动重置只删除当前有效窗口；无日程卡保持兼容，全删该卡流水。
     * 待开始或卡片不存在时不执行破坏性写入。
     */
    suspend fun resetCardCycle(cardId: Long): Boolean =
        database.withTransaction {
            val now = clock.instant()
            val zone = zoneIdProvider()
            val storedCard = cardDao.getById(cardId) ?: return@withTransaction false
            val card = normalizeCardIfOverdue(storedCard, now, zone)
            val cycle = resolveCycle(card, now, zone)
            when (cycle) {
                AnnualFeeCycle.Unscheduled -> transactionDao.deleteAllForCard(cardId)
                is AnnualFeeCycle.Active ->
                    transactionDao.deleteForCardInRange(
                        cardId = cardId,
                        start = cycle.startBoundaryMillis,
                        end = cycle.dueBoundaryMillis,
                    )
                is AnnualFeeCycle.Upcoming -> return@withTransaction false
                AnnualFeeCycle.Overdue -> error("过期周期归一化后仍为 OVERDUE")
            }
            true
        }

    suspend fun deleteTransaction(id: Long): Boolean = transactionDao.deleteById(id) == 1

    fun observeFolders(): Flow<List<CardFolderEntity>> = folderDao.observeAll()

    fun observeFoldersWithCardCounts(): Flow<List<FolderWithCardCount>> = folderDao.observeWithCardCounts()

    suspend fun insertFolder(folder: CardFolderEntity): Long = folderDao.insert(folder)

    /** 排序号分配与插入属于同一个写事务，避免首次加载或快速连点产生重复顺序。 */
    suspend fun createFolder(
        name: String,
        colorArgb: Int,
    ): Long =
        database.withTransaction {
            folderDao.insert(
                CardFolderEntity(
                    name = name,
                    colorArgb = colorArgb,
                    sortOrder = folderDao.nextSortOrder(),
                ),
            )
        }

    suspend fun updateFolder(folder: CardFolderEntity): Boolean = folderDao.update(folder) == 1

    suspend fun deleteFolder(folder: CardFolderEntity): Boolean {
        // 外键 `ON DELETE SET NULL` 会把该文件夹下的卡片归入「未分类」。
        return folderDao.delete(folder) == 1
    }

    /** 事务入口：只推进过期日期，绝不删除历史流水。 */
    suspend fun normalizeOverdueCycles(): Int =
        database.withTransaction {
            normalizeOverdueCyclesInTransaction()
        }

    /** 调用方必须已经持有 Room 写事务，供导入流程复用且避免嵌套事务。 */
    internal suspend fun normalizeOverdueCyclesInTransaction(): Int {
        val now = clock.instant()
        val zone = zoneIdProvider()
        var normalizedCount = 0
        cardDao.listAll().forEach { card ->
            if (resolveCycle(card, now, zone) is AnnualFeeCycle.Overdue) {
                normalizeCardIfOverdue(card, now, zone)
                normalizedCount += 1
            }
        }
        return normalizedCount
    }

    private suspend fun normalizeCardIfOverdue(
        card: CardEntity,
        now: Instant,
        zone: ZoneId,
    ): CardEntity {
        val dueToken = card.nextDueDateMillis ?: return card
        if (resolveCycle(card, now, zone) !is AnnualFeeCycle.Overdue) return card
        val normalized =
            card.copy(
                nextDueDateMillis =
                    AnnualFeeCycle.advanceDueDateUntilFuture(
                        nextDueDateToken = dueToken,
                        now = now,
                        zoneId = zone,
                    ),
            )
        cardDao.update(normalized)
        return normalized
    }

    private fun resolveCycle(
        card: CardEntity,
        now: Instant,
        zone: ZoneId,
    ): AnnualFeeCycle =
        AnnualFeeCycle.resolve(
            nextDueDateToken = card.nextDueDateMillis,
            now = now,
            zoneId = zone,
        )

    private fun deriveCards(
        cards: List<CardEntity>,
        transactions: List<TransactionEntity>,
    ): List<CardWithCount> {
        val byCard = transactions.groupBy { it.cardId }
        val now = clock.instant()
        val zone = zoneIdProvider()
        return cards.map { card ->
            val rows = byCard[card.id].orEmpty()
            val cycle = AnnualFeeCycle.resolve(card.nextDueDateMillis, now, zone)
            CardWithCount(
                card = card,
                currentCount = rows.count { cycle.includes(it.occurredAtMillis) },
                lastSwipeAtMillis = rows.maxOfOrNull { it.occurredAtMillis },
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
