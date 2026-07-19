package com.shuaji.cards.ui.screen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.core.OneShotEventQueue
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.DateToken
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.data.local.cardOrientationEnum
import com.shuaji.cards.data.local.cardTypeEnum
import com.shuaji.cards.data.local.imageSourceTypeEnum
import com.shuaji.cards.data.local.isValidCardMonthDay
import com.shuaji.cards.ui.theme.DEFAULT_BRAND_PRIMARY_ARGB
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

sealed interface CardEditSaveResult {
    data object Idle : CardEditSaveResult

    data class ValidationError(
        val validation: CardEditValidation,
    ) : CardEditSaveResult

    data class Saved(
        val id: Long,
    ) : CardEditSaveResult

    data object Failed : CardEditSaveResult
}

sealed interface CardEditEvent {
    data object ImagePermissionFailed : CardEditEvent

    data object CloseReady : CardEditEvent
}

enum class CardEditValidation {
    NEXT_DUE_MUST_BE_FUTURE,
}

enum class CardEditLoadState {
    NEW,
    LOADING,
    READY,
    MISSING,
    FAILED,
}

/**
 * 表单状态：所有用户可控字段。
 *
 * 不再含 `currentCount`——它由 Repository 从 transactions 按周期派生，编辑表单
 * 也就没有"手动改笔数"这个 UI 入口了（用户唯一改笔数的方式就是
 * 详情页/主页"记一笔"按钮写一条流水）。
 */
data class CardEditUiState(
    val name: String = "",
    val bank: String = "",
    val cardNumberMasked: String = "",
    val cardType: CardType = CardType.UNSPECIFIED,
    val statementDay: String = "",
    val repaymentDay: String = "",
    val requiredCount: String = "6",
    val validUntilMillis: Long? = null,
    val nextDueDateMillis: Long? = null,
    val colorArgb: Int = DEFAULT_BRAND_PRIMARY_ARGB.toInt(),
    val note: String = "",
    // 卡面三态
    val imageSourceType: ImageSourceType = ImageSourceType.PROVIDER,
    val imageProviderKey: String? = CardNetworkProvider.VISA.key,
    val imageUri: String? = null,
    val acquiredImageUris: Set<String> = emptySet(),
    val cardOrientation: CardOrientation = CardOrientation.LANDSCAPE,
    val folderId: Long? = null,
    val loadState: CardEditLoadState = CardEditLoadState.NEW,
    val isSaving: Boolean = false,
    val isClosing: Boolean = false,
    val saveResult: CardEditSaveResult = CardEditSaveResult.Idle,
    val editingId: Long? = null,
) {
    val isStatementDayInvalid: Boolean
        get() = cardType == CardType.CREDIT && !isOptionalCardMonthDayValid(statementDay)

    val isRepaymentDayInvalid: Boolean
        get() = cardType == CardType.CREDIT && !isOptionalCardMonthDayValid(repaymentDay)

    val canSave: Boolean
        get() =
            !isSaving &&
                !isClosing &&
                (loadState == CardEditLoadState.NEW || loadState == CardEditLoadState.READY) &&
                saveResult !is CardEditSaveResult.Saved &&
                name.isNotBlank() &&
                requiredCount.toIntOrNull()?.let { it > 0 } == true &&
                !isStatementDayInvalid &&
                !isRepaymentDayInvalid
}

class CardEditViewModel(
    private val repository: CardRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(CardEditUiState())
    val uiState: StateFlow<CardEditUiState> = _uiState.asStateFlow()
    private val eventQueue = OneShotEventQueue<CardEditEvent>()
    val events: Flow<CardEditEvent> = eventQueue.events
    private var loadJob: Job? = null
    private var permissionCleanupJob: Job? = null
    private var imageSelectionJob: Job? = null
    private var imageSelectionGeneration = 0L
    private var initializedCardId: Long? = null
    private var isInitialized = false
    private var formGeneration = 0L

    /** 供编辑表单下拉选择使用 */
    val folders: StateFlow<List<CardFolderEntity>> =
        repository
            .observeFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 每个导航目的地只初始化一次。配置变更会重建 Composable，但不会清掉同一个
     * NavBackStackEntry 的 ViewModel，因此不能再次覆盖用户尚未保存的表单。
     */
    fun initialize(cardId: Long?) {
        if (isInitialized && initializedCardId == cardId) return
        isInitialized = true
        initializedCardId = cardId
        if (cardId == null) reset() else load(cardId)
    }

    /**
     * 重置表单到初始状态。新建卡片时调用。
     */
    fun reset() {
        isInitialized = true
        initializedCardId = null
        val staleImageUris = _uiState.value.acquiredImageUris
        formGeneration++
        imageSelectionGeneration++
        imageSelectionJob?.cancel()
        loadJob?.cancel()
        _uiState.value = CardEditUiState()
        enqueuePermissionCleanup(staleImageUris)
    }

    /**
     * 加载已有卡片数据。使用 first() 只取一次，避免 Flow 持续订阅。
     */
    fun load(cardId: Long) {
        isInitialized = true
        initializedCardId = cardId
        val staleImageUris = _uiState.value.acquiredImageUris
        formGeneration++
        imageSelectionGeneration++
        imageSelectionJob?.cancel()
        loadJob?.cancel()
        _uiState.value = CardEditUiState(loadState = CardEditLoadState.LOADING)
        enqueuePermissionCleanup(staleImageUris)
        loadJob =
            viewModelScope.launch {
                try {
                    val entity = repository.observeCard(cardId).first()
                    if (entity == null) {
                        _uiState.value = CardEditUiState(loadState = CardEditLoadState.MISSING)
                        return@launch
                    }
                    val c = entity.card
                    _uiState.value =
                        CardEditUiState(
                            name = c.name,
                            bank = c.bank,
                            cardNumberMasked = c.cardNumberMasked,
                            cardType = c.cardTypeEnum,
                            statementDay = c.statementDay?.toString().orEmpty(),
                            repaymentDay = c.repaymentDay?.toString().orEmpty(),
                            requiredCount = c.requiredCount.toString(),
                            validUntilMillis = c.validUntilMillis,
                            nextDueDateMillis = c.nextDueDateMillis,
                            colorArgb = c.colorArgb,
                            note = c.note,
                            imageSourceType = c.imageSourceTypeEnum,
                            imageProviderKey = c.imageProviderKey,
                            imageUri = c.imageUri,
                            acquiredImageUris = emptySet(),
                            cardOrientation = c.cardOrientationEnum,
                            folderId = c.folderId,
                            editingId = c.id,
                            loadState = CardEditLoadState.READY,
                        )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("CardEditViewModel", "加载卡片失败（${error::class.java.simpleName}）")
                    _uiState.value = CardEditUiState(loadState = CardEditLoadState.FAILED)
                }
            }
    }

    fun update(transform: (CardEditUiState) -> CardEditUiState) {
        _uiState.update { current ->
            if (current.isSaving || current.isClosing || current.saveResult is CardEditSaveResult.Saved) {
                current
            } else {
                transform(current).copy(saveResult = CardEditSaveResult.Idle)
            }
        }
    }

    fun selectImageSource(type: ImageSourceType) {
        update { state ->
            state.copy(
                imageSourceType = type,
                imageProviderKey =
                    if (type == ImageSourceType.PROVIDER) {
                        state.imageProviderKey ?: CardNetworkProvider.VISA.key
                    } else {
                        state.imageProviderKey
                    },
            )
        }
    }

    fun selectNetwork(network: CardNetworkProvider?) {
        update { state ->
            state.copy(
                imageSourceType =
                    if (network == null && state.imageSourceType == ImageSourceType.PROVIDER) {
                        ImageSourceType.NONE
                    } else {
                        state.imageSourceType
                    },
                imageProviderKey = network?.key,
            )
        }
    }

    fun selectUserImage(uri: String) {
        val requestGeneration = formGeneration
        val requestSelectionGeneration = ++imageSelectionGeneration
        val previousSelection = imageSelectionJob
        previousSelection?.cancel()
        imageSelectionJob =
            viewModelScope.launch {
                var acquired = false
                var accepted = false
                try {
                    previousSelection?.join()
                    permissionCleanupJob?.join()
                    val current = _uiState.value
                    if (uri in current.acquiredImageUris) {
                        acceptUserImage(uri, requestGeneration, requestSelectionGeneration)
                        return@launch
                    }
                    // 把 suspend 返回值赋给本地变量也纳入不可取消区；否则权限已经取得后，
                    // 恰好在恢复调用方时取消，finally 仍会误以为 acquired=false。
                    withContext(NonCancellable) {
                        acquired = repository.acquireUserImagePermission(uri)
                    }
                    currentCoroutineContext().ensureActive()
                    if (!acquired) {
                        eventQueue.emit(CardEditEvent.ImagePermissionFailed)
                        return@launch
                    }
                    accepted = acceptUserImage(uri, requestGeneration, requestSelectionGeneration)
                } finally {
                    if (acquired && !accepted) {
                        withContext(NonCancellable) {
                            repository.releasePendingUserImagePermissions(setOf(uri))
                        }
                    }
                }
            }
    }

    fun save() {
        val state = beginSaving() ?: return
        viewModelScope.launch {
            try {
                val normalizedDue =
                    state.nextDueDateMillis?.let { token ->
                        val date = normalizeAnnualDueDate(DateToken.toLocalDate(token))
                        val today = clock.instant().atZone(zoneIdProvider()).toLocalDate()
                        val validation = validateNextDue(date, today)
                        if (validation != null) {
                            _uiState.update {
                                it.copy(
                                    nextDueDateMillis = DateToken.fromAnnualDate(date),
                                    isSaving = false,
                                    saveResult = CardEditSaveResult.ValidationError(validation),
                                )
                            }
                            return@launch
                        }
                        DateToken.fromAnnualDate(date)
                    }
                val required = state.requiredCount.toInt()
                val existingId = state.editingId
                val preserved =
                    if (existingId != null) {
                        repository.observeCard(existingId).first()
                    } else {
                        null
                    }
                if (existingId != null && preserved == null) {
                    _uiState.update {
                        it.copy(
                            loadState = CardEditLoadState.MISSING,
                            isSaving = false,
                            saveResult = CardEditSaveResult.Idle,
                        )
                    }
                    return@launch
                }
                val entity =
                    CardEntity(
                        id = existingId ?: 0L,
                        name = state.name.trim(),
                        bank = state.bank.trim(),
                        cardNumberMasked = state.cardNumberMasked.trim(),
                        cardType = state.cardType.key,
                        statementDay = persistedCardMonthDay(state.cardType, state.statementDay),
                        repaymentDay = persistedCardMonthDay(state.cardType, state.repaymentDay),
                        requiredCount = required,
                        validUntilMillis = state.validUntilMillis,
                        nextDueDateMillis = normalizedDue,
                        colorArgb = state.colorArgb,
                        note = state.note,
                        imageUri = persistedImageUri(state.imageSourceType, state.imageUri),
                        imageSourceType = state.imageSourceType.key,
                        imageProviderKey = state.imageProviderKey,
                        cardOrientation = state.cardOrientation.key,
                        folderId = state.folderId,
                        createdAtMillis = preserved?.card?.createdAtMillis ?: clock.millis(),
                    )
                val id =
                    if (existingId == null) {
                        repository.upsertCard(entity)
                    } else {
                        if (!repository.updateCard(entity)) {
                            _uiState.update {
                                it.copy(
                                    loadState = CardEditLoadState.MISSING,
                                    isSaving = false,
                                    saveResult = CardEditSaveResult.Idle,
                                )
                            }
                            return@launch
                        }
                        existingId
                    }
                // 数据已提交后结束临时 URI 租约；取消不能把已完成写入留成永久 pending。
                withContext(NonCancellable) {
                    repository.releasePendingUserImagePermissions(state.acquiredImageUris)
                }
                _uiState.update {
                    it.copy(
                        imageUri = entity.imageUri,
                        imageProviderKey = entity.imageProviderKey,
                        isSaving = false,
                        saveResult = CardEditSaveResult.Saved(id),
                        editingId = id,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("CardEditViewModel", "保存卡片失败（${error::class.java.simpleName}）")
                _uiState.update { it.copy(isSaving = false, saveResult = CardEditSaveResult.Failed) }
            } finally {
                _uiState.update {
                    if (it.saveResult is CardEditSaveResult.Saved) it else it.copy(isSaving = false)
                }
            }
        }
    }

    /** 保存和关闭竞争同一份原子状态；权限收尾归 ViewModel 所有，配置变更不会冻结页面。 */
    fun closeWithoutSaving() {
        val state = beginClosing() ?: return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                repository.releasePendingUserImagePermissions(state.acquiredImageUris)
                eventQueue.emit(CardEditEvent.CloseReady)
            }
        }
    }

    private fun beginClosing(): CardEditUiState? {
        while (true) {
            val current = _uiState.value
            if (current.isSaving || current.isClosing || current.saveResult is CardEditSaveResult.Saved) return null
            if (_uiState.compareAndSet(current, current.copy(isClosing = true))) return current
        }
    }

    /** 串行结束旧表单的临时 URI 租约，避免重载表单与下一次选图互相抢权限。 */
    private fun enqueuePermissionCleanup(uris: Set<String>) {
        if (uris.isEmpty()) return
        val previousCleanup = permissionCleanupJob
        permissionCleanupJob =
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    previousCleanup?.join()
                    repository.releasePendingUserImagePermissions(uris)
                }
            }
    }

    private fun acceptUserImage(
        uri: String,
        requestGeneration: Long,
        requestSelectionGeneration: Long,
    ): Boolean {
        while (true) {
            val current = _uiState.value
            if (
                requestGeneration != formGeneration ||
                requestSelectionGeneration != imageSelectionGeneration ||
                current.isSaving ||
                current.isClosing ||
                current.saveResult is CardEditSaveResult.Saved
            ) {
                return false
            }
            val updated =
                current.copy(
                    imageUri = uri,
                    imageSourceType = ImageSourceType.USER,
                    acquiredImageUris = current.acquiredImageUris + uri,
                )
            if (_uiState.compareAndSet(current, updated)) return true
        }
    }

    private fun beginSaving(): CardEditUiState? {
        while (true) {
            val current = _uiState.value
            if (!current.canSave) return null
            if (_uiState.compareAndSet(current, current.copy(isSaving = true))) return current
        }
    }
}

internal fun normalizeAnnualDueDate(date: LocalDate): LocalDate = DateToken.normalizeAnnualDate(date)

internal fun validateNextDue(
    date: LocalDate,
    today: LocalDate,
): CardEditValidation? = if (date.isAfter(today)) null else CardEditValidation.NEXT_DUE_MUST_BE_FUTURE

internal fun persistedImageUri(
    sourceType: ImageSourceType,
    imageUri: String?,
): String? = imageUri.takeIf { sourceType == ImageSourceType.USER }

internal fun isOptionalCardMonthDayValid(input: String): Boolean = input.isBlank() || parseCardMonthDay(input) != null

internal fun parseCardMonthDay(input: String): Int? = input.toIntOrNull()?.takeIf(Int::isValidCardMonthDay)

internal fun persistedCardMonthDay(
    cardType: CardType,
    input: String,
): Int? = if (cardType == CardType.CREDIT) parseCardMonthDay(input) else null
