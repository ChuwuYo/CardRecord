package com.shuaji.cards.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.core.OneShotEventQueue
import com.shuaji.cards.data.AnnualFeeCycle
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.CardWithCount
import com.shuaji.cards.data.SwipeRecordResult
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.data.local.isExpiredAt
import com.shuaji.cards.data.local.isValidCardMonthDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class CardDetailUi(
    val card: com.shuaji.cards.data.local.CardEntity,
    val currentCount: Int,
    val isExpired: Boolean,
    val lastSwipeAtMillis: Long?,
    val cycle: AnnualFeeCycle,
    val swipes: List<TransactionEntity> = emptyList(),
    val userImageModel: String? = null,
) {
    val requiredCount: Int get() = card.requiredCount
    val selectedCardType: CardType?
        get() = card.cardType.takeUnless { it == CardType.UNSPECIFIED }
    val statementDay: Int?
        get() = card.statementDay?.takeIf { selectedCardType == CardType.CREDIT && it.isValidCardMonthDay() }
    val repaymentDay: Int?
        get() = card.repaymentDay?.takeIf { selectedCardType == CardType.CREDIT && it.isValidCardMonthDay() }
    val hasDetailInfo: Boolean
        get() =
            selectedCardType != null ||
                card.bank.isNotBlank() ||
                card.cardNumberMasked.isNotBlank() ||
                card.validUntilMillis != null ||
                card.nextDueDateMillis != null ||
                statementDay != null ||
                repaymentDay != null ||
                card.note.isNotBlank()

    fun isCurrentPeriod(transaction: TransactionEntity): Boolean = cycle.includes(transaction.occurredAtMillis)
}

sealed interface CardDetailUiState {
    data object Loading : CardDetailUiState

    data class Loaded(
        val detail: CardDetailUi,
    ) : CardDetailUiState

    data object Missing : CardDetailUiState
}

sealed interface CardDetailEvent {
    data object Deleted : CardDetailEvent

    data object DeleteFailed : CardDetailEvent

    data object WriteFailed : CardDetailEvent
}

class CardDetailViewModel(
    private val repository: CardRepository,
    private val cardId: Long,
) : ViewModel() {
    private val nowProvider: () -> Instant = { Instant.now() }
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
    private var deleteJob: Job? = null

    private val swipeFeedbackQueue = OneShotEventQueue<SwipeFeedback>()
    val swipeFeedback: Flow<SwipeFeedback> = swipeFeedbackQueue.events

    private val eventQueue = OneShotEventQueue<CardDetailEvent>()
    val events: Flow<CardDetailEvent> = eventQueue.events

    val uiState: StateFlow<CardDetailUiState> =
        repository
            .observeCardDetails(cardId)
            .map { snapshot ->
                snapshot?.let {
                    CardDetailUiState.Loaded(it.card.toDetailUi(nowProvider(), zoneIdProvider(), it.swipes))
                } ?: CardDetailUiState.Missing
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardDetailUiState.Loading)

    fun recordSwipe() {
        viewModelScope.launch {
            try {
                when (val result = repository.recordSwipe(cardId)) {
                    is SwipeRecordResult.Recorded -> Unit
                    is SwipeRecordResult.CountingNotStarted ->
                        swipeFeedbackQueue.emit(SwipeFeedback.CountingNotStarted(result.startDate))
                    SwipeRecordResult.CardMissing -> swipeFeedbackQueue.emit(SwipeFeedback.CardMissing)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                eventQueue.emit(CardDetailEvent.WriteFailed)
            }
        }
    }

    fun resetCardCycle() {
        launchWrite { repository.resetCardCycle(cardId) }
    }

    fun deleteSwipe(id: Long) {
        launchWrite { repository.deleteTransaction(id) }
    }

    fun deleteCard() {
        if (deleteJob?.isActive == true) return
        val card = (uiState.value as? CardDetailUiState.Loaded)?.detail?.card ?: return
        deleteJob =
            viewModelScope.launch {
                try {
                    val event =
                        if (repository.deleteCard(card)) {
                            CardDetailEvent.Deleted
                        } else {
                            CardDetailEvent.DeleteFailed
                        }
                    eventQueue.emit(event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    eventQueue.emit(CardDetailEvent.DeleteFailed)
                }
            }
    }

    private fun launchWrite(block: suspend () -> Boolean) {
        viewModelScope.launch {
            try {
                if (!block()) eventQueue.emit(CardDetailEvent.WriteFailed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                eventQueue.emit(CardDetailEvent.WriteFailed)
            }
        }
    }
}

private fun CardWithCount.toDetailUi(
    now: Instant,
    zoneId: ZoneId,
    swipes: List<TransactionEntity>,
): CardDetailUi =
    CardDetailUi(
        card = card,
        userImageModel = resolvedUserImageUri,
        currentCount = currentCount,
        isExpired = card.isExpiredAt(now, zoneId),
        lastSwipeAtMillis = lastSwipeAtMillis,
        cycle = cycle,
        swipes = swipes,
    )
