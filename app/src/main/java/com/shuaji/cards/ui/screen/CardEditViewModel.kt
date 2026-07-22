package com.shuaji.cards.ui.screen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shuaji.cards.core.OneShotEventQueue
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.DateToken
import com.shuaji.cards.data.StagedUserImage
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
    data object ImageImportFailed : CardEditEvent

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
 * 编辑表单对用户图片的唯一状态轴。
 *
 * [Unchanged] 不缓存持久化引用，保存时由 DAO 在事务内保留数据库最新值；[Cleared] 与
 * [Selected] 才表示用户明确改动，因而不会把后台刚迁移的图片覆盖回旧快照。
 */
sealed interface UserImageDraft {
    val previewModel: String?

    data class Unchanged(
        override val previewModel: String?,
    ) : UserImageDraft

    data object Cleared : UserImageDraft {
        override val previewModel: String? = null
    }

    data class Selected(
        val staged: StagedUserImage,
    ) : UserImageDraft {
        override val previewModel: String = staged.displayUri
    }
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
    val userImage: UserImageDraft = UserImageDraft.Cleared,
    val cardOrientation: CardOrientation = CardOrientation.LANDSCAPE,
    val folderId: Long? = null,
    val loadState: CardEditLoadState = CardEditLoadState.NEW,
    val isImportingImage: Boolean = false,
    val isSaving: Boolean = false,
    val isClosing: Boolean = false,
    val saveResult: CardEditSaveResult = CardEditSaveResult.Idle,
    val editingId: Long? = null,
) {
    val imageUri: String?
        get() = userImage.previewModel

    val isStatementDayInvalid: Boolean
        get() = cardType == CardType.CREDIT && !isOptionalCardMonthDayValid(statementDay)

    val isRepaymentDayInvalid: Boolean
        get() = cardType == CardType.CREDIT && !isOptionalCardMonthDayValid(repaymentDay)

    val isEditLocked: Boolean
        get() = isSaving || isClosing || saveResult is CardEditSaveResult.Saved

    val canSave: Boolean
        get() =
            !isEditLocked &&
                !isImportingImage &&
                (loadState == CardEditLoadState.NEW || loadState == CardEditLoadState.READY) &&
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
    private var imageCleanupJob: Job? = null
    private var imageSelectionJob: Job? = null
    private var imageSelectionGeneration = 0L
    private var initializedCardId: Long? = null
    private var isInitialized = false
    private var formGeneration = 0L

    @Volatile
    private var isCleared = false

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
        if (cardId == null) reset() else load(cardId)
    }

    private fun reset() = transitionToForm(cardId = null, state = CardEditUiState())

    /** 加载已有卡片数据；只做一次单卡查询，不启动列表所需的全表派生流。 */
    fun load(cardId: Long) {
        transitionToForm(
            cardId = cardId,
            state = CardEditUiState(loadState = CardEditLoadState.LOADING),
        )
        loadJob =
            viewModelScope.launch {
                try {
                    val entity = repository.getStoredCard(cardId)
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
                            userImage = UserImageDraft.Unchanged(entity.resolvedUserImageUri),
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

    /** 所有表单切换共用同一套代际、取消和暂存图片清理规则。 */
    private fun transitionToForm(
        cardId: Long?,
        state: CardEditUiState,
    ) {
        isInitialized = true
        initializedCardId = cardId
        val staleImage = _uiState.value.userImage.stagedOrNull()
        formGeneration++
        imageSelectionGeneration++
        imageSelectionJob?.cancel()
        loadJob?.cancel()
        _uiState.value = state
        enqueueImageCleanup(staleImage)
    }

    fun update(transform: (CardEditUiState) -> CardEditUiState) {
        _uiState.update { current ->
            if (current.isEditLocked) {
                current
            } else {
                transform(current).copy(saveResult = CardEditSaveResult.Idle)
            }
        }
    }

    fun selectImageSource(type: ImageSourceType) {
        if (type != ImageSourceType.USER) invalidatePendingImageSelection()
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

    fun clearUserImage() {
        invalidatePendingImageSelection()
        while (true) {
            val current = _uiState.value
            if (current.isEditLocked) return
            if (
                _uiState.compareAndSet(
                    current,
                    current.copy(userImage = UserImageDraft.Cleared, saveResult = CardEditSaveResult.Idle),
                )
            ) {
                enqueueImageCleanup(current.userImage.stagedOrNull())
                return
            }
        }
    }

    /** 用户后续动作优先于仍在复制的旧选择；存储层会负责回收已完成但未被表单接纳的租约。 */
    private fun invalidatePendingImageSelection() {
        imageSelectionGeneration++
        imageSelectionJob?.cancel()
        _uiState.update { current ->
            if (current.isImportingImage) current.copy(isImportingImage = false) else current
        }
    }

    fun selectUserImage(uri: String) {
        if (!beginImageImport()) return
        val requestGeneration = formGeneration
        val requestSelectionGeneration = ++imageSelectionGeneration
        val previousSelection = imageSelectionJob
        previousSelection?.cancel()
        imageSelectionJob =
            viewModelScope.launch {
                var staged: StagedUserImage? = null
                var accepted = false
                try {
                    previousSelection?.join()
                    imageCleanupJob?.join()
                    // 存储边界负责“文件已落盘但租约尚未返回”这一取消竞态；表单层保持可取消。
                    staged = repository.stageUserImage(uri)
                    currentCoroutineContext().ensureActive()
                    accepted = acceptUserImage(checkNotNull(staged), requestGeneration, requestSelectionGeneration)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("CardEditViewModel", "导入卡面图片失败（${error::class.java.simpleName}）")
                    eventQueue.emit(CardEditEvent.ImageImportFailed)
                } finally {
                    if (staged != null && !accepted) {
                        withContext(NonCancellable) {
                            repository.endPendingUserImageLeases(setOf(checkNotNull(staged)))
                            repository.reclaimUnusedUserImages()
                        }
                    }
                    finishImageImport(requestGeneration, requestSelectionGeneration)
                }
            }
    }

    fun save() {
        val state = beginSaving() ?: return
        val savingLeases = state.userImage.stagedOrNull().asLeaseSet()
        val job =
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
                            repository.getStoredCard(existingId)
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
                    val latest = preserved?.card
                    val selectedImage = (state.userImage as? UserImageDraft.Selected)?.staged
                    val preserveStoredUserImage =
                        state.userImage is UserImageDraft.Unchanged &&
                            latest != null
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
                            imageUri = null,
                            imageAssetId = selectedImage?.assetId?.value,
                            imageSourceType = state.imageSourceType.key,
                            imageProviderKey = state.imageProviderKey,
                            cardOrientation = state.cardOrientation.key,
                            folderId = state.folderId,
                            createdAtMillis = latest?.createdAtMillis ?: clock.millis(),
                        )
                    val id =
                        if (existingId == null) {
                            repository.upsertCard(entity)
                        } else {
                            val updated =
                                if (preserveStoredUserImage) {
                                    repository.updateCardPreservingUserImage(entity)
                                } else {
                                    repository.updateCard(entity)
                                }
                            if (!updated) {
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
                    // 数据已提交后结束暂存租约；数据库引用已成为文件保留的唯一真源。
                    withContext(NonCancellable) {
                        repository.endPendingUserImageLeases(savingLeases)
                    }
                    val saved = repository.getStoredCard(id)
                    val savedEntity = saved?.card ?: entity
                    _uiState.update {
                        it.copy(
                            userImage =
                                UserImageDraft.Unchanged(
                                    saved?.resolvedUserImageUri ?: state.userImage.previewModel,
                                ),
                            imageProviderKey = savedEntity.imageProviderKey,
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
        // ViewModel 永久清除时，租约必须覆盖数据库操作的最终完成；不能在写入途中提前释放。
        job.invokeOnCompletion {
            if (isCleared) repository.endPendingUserImageLeases(savingLeases)
        }
    }

    /** 保存和关闭竞争同一份原子状态；暂存文件收尾归 ViewModel 所有，配置变更不会冻结页面。 */
    fun closeWithoutSaving() {
        invalidatePendingImageSelection()
        val state = beginClosing() ?: return
        val leases = state.userImage.stagedOrNull().asLeaseSet()
        repository.endPendingUserImageLeases(leases)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                repository.reclaimUnusedUserImages()
                eventQueue.emit(CardEditEvent.CloseReady)
            }
        }
    }

    private fun beginClosing(): CardEditUiState? {
        while (true) {
            val current = _uiState.value
            if (current.isEditLocked) return null
            if (_uiState.compareAndSet(current, current.copy(isClosing = true))) return current
        }
    }

    /** 串行结束旧表单的图片租约，避免重载表单与下一次选图互相回收文件。 */
    private fun enqueueImageCleanup(image: StagedUserImage?) {
        val leases = image.asLeaseSet()
        if (leases.isEmpty()) return
        // 即使随后 ViewModelScope 被永久取消，租约也已同步结束；文件留给本次或下次维护回收。
        repository.endPendingUserImageLeases(leases)
        val previousCleanup = imageCleanupJob
        imageCleanupJob =
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    previousCleanup?.join()
                    repository.reclaimUnusedUserImages()
                }
            }
    }

    private fun acceptUserImage(
        staged: StagedUserImage,
        requestGeneration: Long,
        requestSelectionGeneration: Long,
    ): Boolean {
        while (true) {
            val current = _uiState.value
            if (
                requestGeneration != formGeneration ||
                requestSelectionGeneration != imageSelectionGeneration ||
                current.isEditLocked
            ) {
                return false
            }
            val updated =
                current.copy(
                    userImage = UserImageDraft.Selected(staged),
                    imageSourceType = ImageSourceType.USER,
                    isImportingImage = false,
                )
            if (_uiState.compareAndSet(current, updated)) {
                enqueueImageCleanup(current.userImage.stagedOrNull())
                return true
            }
        }
    }

    private fun beginSaving(): CardEditUiState? {
        while (true) {
            val current = _uiState.value
            if (!current.canSave) return null
            if (_uiState.compareAndSet(current, current.copy(isSaving = true))) return current
        }
    }

    private fun beginImageImport(): Boolean {
        while (true) {
            val current = _uiState.value
            if (current.isEditLocked) return false
            val importing = current.copy(isImportingImage = true, saveResult = CardEditSaveResult.Idle)
            if (_uiState.compareAndSet(current, importing)) return true
        }
    }

    private fun finishImageImport(
        requestGeneration: Long,
        requestSelectionGeneration: Long,
    ) {
        if (requestGeneration != formGeneration || requestSelectionGeneration != imageSelectionGeneration) return
        _uiState.update { current ->
            if (current.isImportingImage) current.copy(isImportingImage = false) else current
        }
    }

    override fun onCleared() {
        isCleared = true
        val state = _uiState.value
        if (!state.isSaving) {
            repository.endPendingUserImageLeases(state.userImage.stagedOrNull().asLeaseSet())
        }
        super.onCleared()
    }
}

internal fun normalizeAnnualDueDate(date: LocalDate): LocalDate = DateToken.normalizeAnnualDate(date)

internal fun validateNextDue(
    date: LocalDate,
    today: LocalDate,
): CardEditValidation? = if (date.isAfter(today)) null else CardEditValidation.NEXT_DUE_MUST_BE_FUTURE

internal fun isOptionalCardMonthDayValid(input: String): Boolean = input.isBlank() || parseCardMonthDay(input) != null

internal fun parseCardMonthDay(input: String): Int? = input.toIntOrNull()?.takeIf(Int::isValidCardMonthDay)

internal fun persistedCardMonthDay(
    cardType: CardType,
    input: String,
): Int? = if (cardType == CardType.CREDIT) parseCardMonthDay(input) else null

private fun UserImageDraft.stagedOrNull(): StagedUserImage? = (this as? UserImageDraft.Selected)?.staged

private fun StagedUserImage?.asLeaseSet(): Set<StagedUserImage> = this?.let(::setOf).orEmpty()
