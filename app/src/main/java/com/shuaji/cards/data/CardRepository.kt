package com.shuaji.cards.data

import android.util.Log
import androidx.room.withTransaction
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.FolderWithCardCount
import com.shuaji.cards.data.local.TransactionDao
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.data.local.withNormalizedCreditDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

class CardRepository(
    private val database: AppDatabase,
    private val cardDao: CardDao,
    private val transactionDao: TransactionDao,
    private val folderDao: CardFolderDao,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    boundaryTicks: Flow<Unit>,
    private val userImages: UserCardImageStore,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
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
        }.flowOn(defaultDispatcher)

    fun observeCards(): Flow<List<CardWithCount>> = derivedSnapshots.map { it.cards }

    suspend fun getStoredCard(id: Long): StoredCardSnapshot? =
        cardDao.getById(id)?.let { card ->
            StoredCardSnapshot(
                card = card,
                resolvedUserImageUri = resolveUserImage(card.imageAssetId, card.imageUri),
            )
        }

    fun observeCardDetails(id: Long): Flow<CardDetailsSnapshot?> =
        derivedSnapshots
            .map { snapshot ->
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
            }.flowOn(defaultDispatcher)

    suspend fun upsertCard(card: CardEntity): Long {
        val id = cardDao.upsert(card.withNormalizedCreditDetails())
        if (card.id != 0L) collectUserImageGarbageAfterWrite()
        return id
    }

    suspend fun updateCard(card: CardEntity): Boolean {
        val updated = cardDao.update(card.withNormalizedCreditDetails()) == 1
        if (updated) collectUserImageGarbageAfterWrite()
        return updated
    }

    suspend fun updateCardPreservingUserImage(card: CardEntity): Boolean =
        cardDao.updatePreservingUserImage(card.withNormalizedCreditDetails()) == 1

    suspend fun deleteCard(card: CardEntity): Boolean {
        val deleted = cardDao.delete(card) == 1
        if (deleted) collectUserImageGarbageAfterWrite()
        return deleted
    }

    suspend fun stageUserImage(uri: String): StagedUserImage = userImages.stageFromUri(uri)

    fun endPendingUserImageLeases(images: Set<StagedUserImage>) {
        userImages.releaseLeases(images)
    }

    suspend fun reclaimUnusedUserImages() = collectUserImageGarbageBestEffort()

    private fun resolveUserImage(
        assetId: String?,
        legacyUri: String?,
    ): String? =
        ImageAssetId
            .parse(assetId)
            ?.let(userImages::resolve)
            ?.takeIf(String::isNotBlank)
            ?: legacyUri?.takeIf(String::isNotBlank)

    private suspend fun collectUserImageGarbageAfterWrite() {
        withContext(NonCancellable) {
            collectUserImageGarbageBestEffort()
        }
    }

    internal suspend fun maintainUserImagesOnStartupBestEffort() {
        try {
            userImages.migrateLegacyImages()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "迁移历史卡面图片失败（${error::class.java.simpleName}）")
        }
        collectUserImageGarbageBestEffort()
    }

    private suspend fun collectUserImageGarbageBestEffort() {
        try {
            userImages.collectGarbage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "回收卡面图片失败（${error::class.java.simpleName}）")
        }
    }

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

    suspend fun deleteFolder(folder: CardFolderEntity): Boolean = folderDao.delete(folder) == 1

    suspend fun normalizeOverdueCycles(): Int =
        database.withTransaction {
            normalizeOverdueCyclesInTransaction()
        }

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
                resolvedUserImageUri = resolveUserImage(card.imageAssetId, card.imageUri),
            )
        }
    }

    private data class DerivedSnapshot(
        val cards: List<CardWithCount>,
        val transactions: List<TransactionEntity>,
    )

    private companion object {
        const val TAG = "CardRepository"
    }
}
