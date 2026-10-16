package com.shuaji.cards.data.backup

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.room.withTransaction
import com.shuaji.cards.R
import com.shuaji.cards.core.AppLocales
import com.shuaji.cards.data.ImageAssetId
import com.shuaji.cards.data.StagedUserImage
import com.shuaji.cards.data.UserCardImageStore
import com.shuaji.cards.data.UserImageImportException
import com.shuaji.cards.data.UserImageMissingException
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.TransactionDao
import com.shuaji.cards.data.local.isValidCardMonthDay
import com.shuaji.cards.data.reminder.AnnualFeeReminderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val DEFAULT_MAX_BACKUP_BYTES: Long = 16L * 1024L * 1024L
private const val DEFAULT_MAX_BACKUP_IMAGE_BYTES: Long = 32L * 1024L * 1024L

class BackupRepository internal constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val cardDao: CardDao,
    private val folderDao: CardFolderDao,
    private val transactionDao: TransactionDao,
    private val normalizeInTransaction: suspend () -> Int,
    private val userImages: UserCardImageStore,
    private val reminderStore: AnnualFeeReminderStore,
    private val onRemindersPreferenceApplied: () -> Unit = {},
    private val cancelTrackedReminders: () -> Unit = {},
    private val maxBackupBytes: Long = DEFAULT_MAX_BACKUP_BYTES,
    private val maxImageBytes: Long = DEFAULT_MAX_BACKUP_IMAGE_BYTES,
    private val directoryAccess: BackupDirectoryAccess = AndroidBackupDirectoryAccess(context),
    private val backupDirectoryName: () -> String = ::defaultBackupDirectoryName,
) {
    private val strings: Context get() = AppLocales.localize(context)

    init {
        require(maxBackupBytes > 0L) { "maxBackupBytes 必须大于 0" }
        require(maxImageBytes > 0L) { "maxImageBytes 必须大于 0" }
    }

    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }
    private val operationMutex = Mutex()

    @Volatile
    private var activeOperation: ActiveOperation? = null

    fun cancelActive(): BackupCancelResult = activeOperation?.cancelIfPossible() ?: BackupCancelResult.NO_ACTIVE_OPERATION

    suspend fun inspect(directoryUri: Uri): BackupPreview =
        runExclusive {
            val decoded = readAndValidateManifest(directoryUri)
            validateDirectoryImages(decoded.bundle, decoded.directory)
            val bundle = decoded.bundle
            BackupPreview(
                cardCount = bundle.cards.size,
                folderCount = bundle.folders.size,
                transactionCount = bundle.transactions.size,
                lastModifiedMillis = decoded.lastModifiedMillis,
                manifestSha256 = decoded.manifestSha256,
            )
        }

    suspend fun export(parentDirectoryUri: Uri): ExportSummary =
        runExclusive {
            userImages.migrateLegacyImages()
            val bundle =
                database.withTransaction {
                    val cards = cardDao.listAll()
                    validateOwnedImages(cards)
                    BackupBundle(
                        version = BackupBundle.SCHEMA_VERSION,
                        cards = cards.map { it.toBackup() },
                        folders = folderDao.listAll().map { it.toBackup() },
                        transactions = transactionDao.listAll().map { it.toBackup() },
                        settings =
                            BackupSettings(
                                annualFeeRemindersEnabled = reminderStore.isEnabled(),
                            ),
                    )
                }
            val cancellationSignal = checkNotNull(activeOperation).cancellationSignal
            val operationJob = checkNotNull(currentCoroutineContext()[Job])
            var target: BackupDirectory? = null
            var completed = false
            try {
                preflightManifest(bundle, operationJob, cancellationSignal)
                val assetIds = referencedAssetIds(bundle).sortedBy(ImageAssetId::value)
                preflightAssetSizes(assetIds, operationJob, cancellationSignal)
                cancellationSignal.throwIfCanceled()
                val createdTarget =
                    directoryAccess.createBackup(parentDirectoryUri, backupDirectoryName(), cancellationSignal)
                target = createdTarget
                assetIds.forEach { assetId ->
                    writeAsset(createdTarget, assetId, operationJob, cancellationSignal)
                }
                cancellationSignal.throwIfCanceled()
                val manifestOutput =
                    createdTarget.openManifestOutput(cancellationSignal)
                        ?: throw BackupException(strings.getString(R.string.backup_error_open_target_uri))
                useTracked(manifestOutput) { out ->
                    @OptIn(ExperimentalSerializationApi::class)
                    json.encodeToStream(BackupBundle.serializer(), bundle, out)
                    out.flush()
                }
                completed = true
                ExportSummary(
                    cardCount = bundle.cards.size,
                    folderCount = bundle.folders.size,
                    transactionCount = bundle.transactions.size,
                    directoryName = createdTarget.displayName,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                throw e
            } catch (e: BackupSizeLimitExceededException) {
                throw BackupException(
                    strings.getString(
                        R.string.backup_error_file_too_large,
                        e.maxBytes.toMebibytesRoundedUp(),
                    ),
                    e,
                )
            } catch (e: UserImageMissingException) {
                throw BackupException(strings.getString(R.string.backup_error_unowned_image), e)
            } catch (e: UserImageImportException) {
                throw BackupException(strings.getString(R.string.backup_error_image_restore_failed), e)
            } catch (e: OperationCanceledException) {
                throw BackupException(
                    strings.getString(R.string.backup_error_write_failed),
                    e,
                )
            } catch (e: IOException) {
                throw BackupException(
                    strings.getString(R.string.backup_error_write_failed),
                    e,
                )
            } catch (e: Exception) {
                throw BackupException(strings.getString(R.string.backup_error_write_failed), e)
            } finally {
                if (!completed) {
                    withContext(NonCancellable) { deleteIncompleteBackup(target) }
                }
            }
        }

    suspend fun import(
        directoryUri: Uri,
        mode: ImportMode,
        expectedManifestSha256: String,
    ): ImportResult =
        runExclusive {
            val decoded = readAndValidateManifest(directoryUri)
            if (decoded.manifestSha256 != expectedManifestSha256) {
                throw BackupException(strings.getString(R.string.backup_error_file_changed))
            }
            val stagedImages = stageDirectoryImages(decoded.bundle, decoded.directory)
            try {
                val bundle = decoded.bundle

                try {
                    val importResult =
                        database.withTransaction {
                            val result =
                                when (mode) {
                                    ImportMode.REPLACE -> doReplace(bundle)
                                    ImportMode.MERGE -> doMerge(bundle)
                                }
                            normalizeInTransaction()
                            enterCommitBoundary()
                            result
                        }
                    // 偏好写在 DB 事务外：SharedPreferences 无法随 SQLite 回滚。
                    val remindersEnabledForPermission =
                        applyReminderSettings(
                            settings = bundle.settings,
                            replaceImport = mode == ImportMode.REPLACE,
                        )
                    importResult.copy(
                        annualFeeRemindersEnabled = remindersEnabledForPermission,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    throw e
                } catch (e: Exception) {
                    throw BackupException(
                        strings.getString(R.string.backup_error_db_write_failed),
                        e,
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    userImages.releaseLeases(stagedImages)
                    collectImageGarbageBestEffort()
                }
            }
        }

    private fun applyReminderSettings(
        settings: BackupSettings,
        replaceImport: Boolean,
    ): Boolean {
        if (!replaceImport) return false
        return try {
            cancelTrackedReminders()
            reminderStore.clearScheduleAndNotifiedState()
            reminderStore.setEnabled(settings.annualFeeRemindersEnabled)
            onRemindersPreferenceApplied()
            settings.annualFeeRemindersEnabled
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            Log.w(BACKUP_LOG_TAG, "applyReminderSettings failed after DB commit", error)
            settings.annualFeeRemindersEnabled
        }
    }

    private suspend fun readAndValidateManifest(directoryUri: Uri): DecodedManifest {
        val decoded = readDirectoryManifest(directoryUri)
        val bundle = decoded.bundle
        currentCoroutineContext().ensureActive()
        if (bundle.version != BackupBundle.SCHEMA_VERSION) {
            throw BackupException(
                strings.getString(R.string.backup_error_version_mismatch),
            )
        }
        if (!bundle.hasValidStructure()) {
            throw BackupException(strings.getString(R.string.backup_error_invalid_structure))
        }
        return decoded
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readDirectoryManifest(directoryUri: Uri): DecodedManifest {
        try {
            val cancellationSignal = checkNotNull(activeOperation).cancellationSignal
            val directory = directoryAccess.openBackup(directoryUri, cancellationSignal)
            val raw =
                directory.openManifestInput(cancellationSignal)
                    ?: throw BackupException(strings.getString(R.string.backup_error_open_source_uri))
            val bounded = SizeLimitedInputStream(raw, maxBackupBytes)
            val digest = MessageDigest.getInstance(SHA_256)
            val input = PushbackInputStream(BufferedInputStream(DigestInputStream(bounded, digest)), 1)
            val bundle =
                useTracked(input) { tracked ->
                    skipWhitespaceOrThrowEmpty(tracked)
                    json.decodeFromStream(BackupBundle.serializer(), tracked)
                }
            return DecodedManifest(
                bundle = bundle,
                manifestSha256 = digest.digest().toLowerHex(),
                directory = directory,
                lastModifiedMillis = directory.lastModifiedMillis,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: BackupSizeLimitExceededException) {
            throw BackupException(
                strings.getString(
                    R.string.backup_error_file_too_large,
                    e.maxBytes.toMebibytesRoundedUp(),
                ),
                e,
            )
        } catch (e: FileNotFoundException) {
            throw BackupException(
                strings.getString(R.string.backup_error_source_missing),
                e,
            )
        } catch (e: SerializationException) {
            throw BackupException(strings.getString(R.string.backup_error_malformed_json), e)
        } catch (e: UserImageImportException) {
            throw BackupException(strings.getString(R.string.backup_error_image_restore_failed), e)
        } catch (e: OperationCanceledException) {
            throw BackupException(
                strings.getString(R.string.backup_error_read_failed),
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw BackupException(strings.getString(R.string.backup_error_malformed_json), e)
        } catch (e: IOException) {
            throw BackupException(
                strings.getString(R.string.backup_error_read_failed),
                e,
            )
        }
    }

    private fun validateOwnedImages(cards: List<CardEntity>) {
        val hasUnownedLegacyImage =
            cards.any { card ->
                card.imageAssetId.isNullOrBlank() &&
                    !card.imageUri.isNullOrBlank()
            }
        if (hasUnownedLegacyImage) {
            throw BackupException(strings.getString(R.string.backup_error_unowned_image))
        }
    }

    private fun referencedAssetIds(bundle: BackupBundle): Set<ImageAssetId> =
        bundle.cards
            .mapNotNull { card ->
                val raw = card.imageAssetId ?: return@mapNotNull null
                ImageAssetId.parse(raw)
                    ?: throw BackupException(strings.getString(R.string.backup_error_invalid_structure))
            }.toSet()

    @OptIn(ExperimentalSerializationApi::class)
    private fun preflightManifest(
        bundle: BackupBundle,
        job: Job,
        cancellationSignal: CancellationSignal,
    ) {
        json.encodeToStream(
            BackupBundle.serializer(),
            bundle,
            SizeLimitedOutputStream(maxBackupBytes, job, cancellationSignal),
        )
    }

    private fun preflightAssetSizes(
        assetIds: List<ImageAssetId>,
        job: Job,
        cancellationSignal: CancellationSignal,
    ) {
        assetIds.forEach { assetId ->
            job.ensureActive()
            cancellationSignal.throwIfCanceled()
            val size = userImages.assetSize(assetId)
            if (size !in 1..maxImageBytes) throw BackupSizeLimitExceededException(maxImageBytes)
        }
    }

    private suspend fun writeAsset(
        directory: BackupDirectory,
        assetId: ImageAssetId,
        job: Job,
        cancellationSignal: CancellationSignal,
    ) {
        val output =
            directory.openImageOutput(assetId.fileName, cancellationSignal)
                ?: throw BackupException(strings.getString(R.string.backup_error_open_target_uri))
        useTracked(output) { trackedOutput ->
            userImages.openAsset(assetId).use { input ->
                copyAndVerifyAsset(
                    input = input,
                    output = trackedOutput,
                    expectedAssetId = assetId,
                    maxBytes = maxImageBytes,
                    job = job,
                    cancellationSignal = cancellationSignal,
                )
            }
            trackedOutput.flush()
        }
    }

    private suspend fun stageDirectoryImages(
        bundle: BackupBundle,
        directory: BackupDirectory,
    ): Set<StagedUserImage> {
        val staged = linkedSetOf<StagedUserImage>()
        var completed = false
        try {
            processReferencedImages(bundle, directory) { assetId, input ->
                staged += userImages.stageFromBackup(assetId, input, maxImageBytes)
            }
            completed = true
            return staged
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw BackupException(strings.getString(R.string.backup_error_image_restore_failed), error)
        } finally {
            if (!completed) {
                withContext(NonCancellable) { releasePendingImagesBestEffort(staged) }
            }
        }
    }

    private suspend fun validateDirectoryImages(
        bundle: BackupBundle,
        directory: BackupDirectory,
    ) {
        try {
            processReferencedImages(bundle, directory) { assetId, input ->
                userImages.validateBackupImage(assetId, input, maxImageBytes)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw BackupException(strings.getString(R.string.backup_error_image_restore_failed), error)
        }
    }

    private suspend fun processReferencedImages(
        bundle: BackupBundle,
        directory: BackupDirectory,
        process: suspend (ImageAssetId, InputStream) -> Unit,
    ) {
        val operation = checkNotNull(activeOperation)
        val assetIds = referencedAssetIds(bundle).sortedBy(ImageAssetId::value)
        directory.indexImageInputs(assetIds.mapTo(linkedSetOf()) { it.fileName }, operation.cancellationSignal)
        assetIds.forEach { assetId ->
            currentCoroutineContext().ensureActive()
            operation.cancellationSignal.throwIfCanceled()
            val input =
                directory.openImageInput(assetId.fileName, operation.cancellationSignal)
                    ?: throw UserImageImportException()
            useTracked(input) { tracked -> process(assetId, tracked) }
        }
    }

    private suspend fun releasePendingImagesBestEffort(images: Set<StagedUserImage>) {
        if (images.isEmpty()) return
        userImages.releaseLeases(images)
        collectImageGarbageBestEffort()
    }

    private suspend fun collectImageGarbageBestEffort() {
        try {
            userImages.collectGarbage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(BACKUP_LOG_TAG, "回收卡面图片失败（${error::class.java.simpleName}）")
        }
    }

    private fun copyAndVerifyAsset(
        input: InputStream,
        output: OutputStream,
        expectedAssetId: ImageAssetId,
        maxBytes: Long,
        job: Job,
        cancellationSignal: CancellationSignal,
    ): Long {
        val digest = MessageDigest.getInstance(SHA_256)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var totalBytes = 0L
        while (true) {
            job.ensureActive()
            cancellationSignal.throwIfCanceled()
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            if (count.toLong() > maxBytes - totalBytes) throw BackupSizeLimitExceededException(maxBytes)
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            totalBytes += count
        }
        if (totalBytes == 0L || ImageAssetId.fromDigest(digest.digest()) != expectedAssetId) {
            throw UserImageImportException()
        }
        return totalBytes
    }

    private fun deleteIncompleteBackup(directory: BackupDirectory?) {
        if (directory == null) return
        try {
            if (!directory.delete()) Log.w(BACKUP_LOG_TAG, "未能删除中断的备份目录")
        } catch (cancelled: CancellationException) {
            Log.w(BACKUP_LOG_TAG, "删除中断的备份目录被取消")
        } catch (error: Exception) {
            Log.w(BACKUP_LOG_TAG, "删除中断的备份目录失败（${error::class.java.simpleName}）")
        }
    }

    private suspend fun <T> runExclusive(block: suspend () -> T): T {
        if (!operationMutex.tryLock()) {
            throw BackupException(strings.getString(R.string.backup_error_operation_busy))
        }
        val operation = ActiveOperation(checkNotNull(currentCoroutineContext()[Job]))
        activeOperation = operation
        return try {
            try {
                coroutineScope {
                    // 普通 Job.cancel() 也必须取消 Provider 并关流；只依赖 finally 会被阻塞 I/O 卡住。
                    val operationFinished = AtomicBoolean(false)
                    val resourceCloser =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                if (!operationFinished.get()) operation.cancelBlockingIo()
                            }
                        }
                    try {
                        withContext(Dispatchers.IO) { block() }.also { operationFinished.set(true) }
                    } finally {
                        resourceCloser.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                operation.ensureCallerActive()
                throw e
            }
        } finally {
            try {
                operation.closeResource()
            } finally {
                if (activeOperation === operation) activeOperation = null
                operationMutex.unlock()
            }
        }
    }

    private suspend fun enterCommitBoundary() {
        currentCoroutineContext().ensureActive()
        if (!checkNotNull(activeOperation).beginCommit()) {
            throw CancellationException("Backup operation cancelled before commit")
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun <T : Closeable, R> useTracked(
        resource: T,
        block: suspend (T) -> R,
    ): R {
        val operation = checkNotNull(activeOperation)
        operation.register(resource)
        var failure: Throwable? = null
        return try {
            block(resource)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                operation.release(resource)
            } catch (closeError: Throwable) {
                failure?.addSuppressed(closeError) ?: throw closeError
            }
        }
    }

    private fun skipWhitespaceOrThrowEmpty(input: PushbackInputStream) {
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                throw BackupException(strings.getString(R.string.backup_error_empty_file))
            }
            if (byte !in JSON_WHITESPACE_BYTES) {
                input.unread(byte)
                return
            }
        }
    }

    private suspend fun doReplace(bundle: BackupBundle): ImportResult {
        cardDao.deleteAll()
        folderDao.deleteAll()
        val folderRemap = mutableMapOf<Long, Long>()
        bundle.folders.forEach { folder ->
            folderRemap[folder.id] = folderDao.insert(folder.toEntity().copy(id = 0L))
        }
        val cardRemap = mutableMapOf<Long, Long>()
        var invalidCount = 0
        bundle.cards.forEach { card ->
            val safeFolderId =
                card.folderId?.let { oldFolderId ->
                    folderRemap[oldFolderId] ?: run {
                        invalidCount++
                        null
                    }
                }
            cardRemap[card.id] =
                cardDao.upsert(
                    card.toEntity().copy(id = 0L, folderId = safeFolderId),
                )
        }
        bundle.transactions.forEach { transaction ->
            val cardId = checkNotNull(cardRemap[transaction.cardId])
            transactionDao.insert(transaction.toEntity().copy(id = 0L, cardId = cardId))
        }
        return ImportResult(
            cardsAdded = bundle.cards.size,
            foldersAdded = bundle.folders.size,
            transactionsAdded = bundle.transactions.size,
            cardsSkippedInvalidFolder = invalidCount,
        )
    }

    private suspend fun doMerge(bundle: BackupBundle): ImportResult {
        val existingFolders = folderDao.listAll()
        val existingFolderNames = existingFolders.map { it.name }.toSet()
        val existingCardNames = cardDao.listAll().map { it.name }.toSet()

        val folderRemap = mutableMapOf<Long, Long>()
        bundle.folders.forEach { folder ->
            val newId = folderDao.insert(folder.toEntity().copy(id = 0L))
            folderRemap[folder.id] = newId
        }
        val cardRemap = mutableMapOf<Long, Long>()
        var invalidCount = 0
        bundle.cards.forEach { card ->
            val safeFolderId =
                card.folderId?.let { oldFolderId ->
                    folderRemap[oldFolderId] ?: run {
                        invalidCount++
                        null
                    }
                }
            val newId =
                cardDao.upsert(
                    card.toEntity().copy(id = 0L, folderId = safeFolderId),
                )
            cardRemap[card.id] = newId
        }

        var txCount = 0
        var txSkipped = 0
        bundle.transactions.forEach { txn ->
            val mappedCardId = cardRemap[txn.cardId]
            if (mappedCardId == null) {
                txSkipped++
                return@forEach
            }
            transactionDao.insert(txn.toEntity().copy(id = 0L, cardId = mappedCardId))
            txCount++
        }

        val duplicateFolders = bundle.folders.count { it.name in existingFolderNames }
        val duplicateCards = bundle.cards.count { it.name in existingCardNames }

        return ImportResult(
            cardsAdded = bundle.cards.size,
            foldersAdded = bundle.folders.size,
            transactionsAdded = txCount,
            transactionsSkipped = txSkipped,
            cardsSkippedInvalidFolder = invalidCount,
            duplicateFolderNames = duplicateFolders,
            duplicateCardNames = duplicateCards,
        )
    }
}

private const val MEBIBYTE: Long = 1024L * 1024L
private const val SHA_256 = "SHA-256"
private const val BACKUP_LOG_TAG = "BackupRepository"
private const val COPY_BUFFER_BYTES = 32 * 1024
private val JSON_WHITESPACE_BYTES = setOf(' '.code, '\t'.code, '\r'.code, '\n'.code)

private data class DecodedManifest(
    val bundle: BackupBundle,
    val manifestSha256: String,
    val directory: BackupDirectory,
    val lastModifiedMillis: Long?,
)

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

private fun Long.toMebibytesRoundedUp(): Long = (this + MEBIBYTE - 1L) / MEBIBYTE

private val ImageAssetId.fileName: String get() = "$value$BACKUP_IMAGE_FILE_SUFFIX"

private fun defaultBackupDirectoryName(): String = BACKUP_DIRECTORY_NAME_FORMATTER.format(LocalDateTime.now())

private val BACKUP_DIRECTORY_NAME_FORMATTER = DateTimeFormatter.ofPattern("'CardRecord_Backup_'yyyyMMdd_HHmmss")

private fun BackupBundle.hasValidStructure(): Boolean {
    fun List<Long>.containsUniquePositiveIds(): Boolean = all { it > 0L } && distinct().size == size

    return folders.map { it.id }.containsUniquePositiveIds() &&
        cards.map { it.id }.containsUniquePositiveIds() &&
        transactions.map { it.id }.containsUniquePositiveIds() &&
        cards.all { card ->
            val parsedType = CardType.fromKeyOrNull(card.cardType)
            card.requiredCount > 0 &&
                (card.folderId == null || card.folderId > 0L) &&
                (card.imageAssetId == null || ImageAssetId.parse(card.imageAssetId) != null) &&
                parsedType != null &&
                (card.statementDay == null || card.statementDay.isValidCardMonthDay()) &&
                (card.repaymentDay == null || card.repaymentDay.isValidCardMonthDay())
        } &&
        transactions.all { it.cardId > 0L }
}

private class ActiveOperation(
    private val job: Job,
) {
    private val resource = AtomicReference<Closeable?>()
    private val phase = AtomicReference(OperationPhase.CANCELLABLE)
    val cancellationSignal = CancellationSignal()

    fun register(value: Closeable) {
        if (!resource.compareAndSet(null, value)) {
            value.closeBestEffort()
            error("一个备份操作不能同时持有多个文件流")
        }
        if (!job.isActive) {
            cancelBlockingIo()
            throw CancellationException("Backup operation cancelled")
        }
    }

    fun release(value: Closeable) {
        if (resource.compareAndSet(value, null)) value.close()
    }

    fun cancelIfPossible(): BackupCancelResult =
        if (phase.compareAndSet(OperationPhase.CANCELLABLE, OperationPhase.CANCELLED)) {
            job.cancel(CancellationException("User cancelled backup operation"))
            cancelBlockingIo()
            BackupCancelResult.CANCELLED
        } else {
            when (phase.get()) {
                OperationPhase.COMMITTING -> BackupCancelResult.COMMIT_IN_PROGRESS
                OperationPhase.CANCELLED -> BackupCancelResult.CANCELLED
                OperationPhase.CANCELLABLE -> error("取消阶段 CAS 失败后不应仍为 CANCELLABLE")
            }
        }

    fun beginCommit(): Boolean = phase.compareAndSet(OperationPhase.CANCELLABLE, OperationPhase.COMMITTING)

    fun ensureCallerActive() {
        job.ensureActive()
    }

    fun cancelBlockingIo() {
        try {
            cancellationSignal.cancel()
        } finally {
            closeResource()
        }
    }

    fun closeResource() {
        resource.getAndSet(null)?.closeBestEffort()
    }
}

private fun Closeable.closeBestEffort() {
    try {
        close()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

private enum class OperationPhase {
    CANCELLABLE,
    COMMITTING,
    CANCELLED,
}

private class BackupSizeLimitExceededException(
    val maxBytes: Long,
) : IOException()

private class SizeLimitedOutputStream(
    private val maxBytes: Long,
    private val job: Job,
    private val cancellationSignal: CancellationSignal,
) : OutputStream() {
    private var bytesWritten = 0L

    init {
        require(maxBytes > 0L) { "maxBytes 必须大于 0" }
    }

    override fun write(byte: Int) {
        ensureNotCancelled()
        reserve(1L)
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        ensureNotCancelled()
        reserve(length.toLong())
    }

    override fun flush() {
        ensureNotCancelled()
    }

    private fun ensureNotCancelled() {
        job.ensureActive()
        cancellationSignal.throwIfCanceled()
    }

    private fun reserve(byteCount: Long) {
        if (byteCount > maxBytes - bytesWritten) throw BackupSizeLimitExceededException(maxBytes)
        bytesWritten += byteCount
    }
}

private class SizeLimitedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
) : InputStream() {
    private var bytesRead = 0L

    init {
        require(maxBytes > 0L) { "maxBytes 必须大于 0" }
    }

    override fun read(): Int {
        if (bytesRead >= maxBytes) return readPastLimit()
        return delegate.read().also { if (it != -1) bytesRead++ }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRead >= maxBytes) return readPastLimit()
        val allowed = minOf(length.toLong(), maxBytes - bytesRead).toInt()
        return delegate.read(buffer, offset, allowed).also { count ->
            if (count > 0) bytesRead += count
        }
    }

    override fun available(): Int = minOf(delegate.available().toLong(), maxBytes - bytesRead).coerceAtLeast(0L).toInt()

    override fun close() {
        delegate.close()
    }

    private fun readPastLimit(): Int =
        if (delegate.read() == -1) {
            -1
        } else {
            throw BackupSizeLimitExceededException(maxBytes)
        }
}

data class ExportSummary(
    val cardCount: Int,
    val folderCount: Int,
    val transactionCount: Int,
    val directoryName: String,
) {
    val total: Int get() = cardCount + folderCount + transactionCount

    val isEmpty: Boolean get() = total == 0
}
