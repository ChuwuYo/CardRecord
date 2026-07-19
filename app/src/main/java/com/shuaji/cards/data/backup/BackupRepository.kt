package com.shuaji.cards.data.backup

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.shuaji.cards.R
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.data.local.TransactionDao
import com.shuaji.cards.data.local.isValidCardMonthDay
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val DEFAULT_MAX_BACKUP_BYTES: Long = 16L * 1024L * 1024L

/**
 * 导入 / 导出仓库。
 *
 * 设计哲学：用户对自己所有数据（卡 / 文件夹 / 流水）拥有**导出和恢复**的权利。
 * 文件格式 JSON，方便人工 inspect / 编辑 / 跨平台处理。
 *
 * 导入两种模式（用户在 UI 选）：
 * - [ImportMode.REPLACE]：清空现有数据库，写入备份
 *   - 清理顺序：cards（顺带 CASCADE 删 transactions）→ folders
 *   - 写入顺序：folders → cards → transactions
 * - [ImportMode.MERGE]：保留现有数据，把备份追加
 *   - 写入顺序：folders → cards → transactions
 *   - 因为备份里的 cards / folders / transactions 自带 id，但 MERGE 模式下这些 id
 *     跟现有数据库可能冲突，**所以写回时把 id 清零让 SQLite 重新分配**，并记录
 *     `oldId -> newId` 映射；transactions 的 cardId 跟着改
 *
 * 文件 I/O 走 ContentResolver（用 URI），用户通过 SAF 选位置——不申请任何存储权限。
 *
 * **事务保障**：所有 REPLACE / MERGE 的写库操作都包在
 * [AppDatabase.withTransaction] 里。任意一步抛异常 / 协程被取消，SQLite 自动
 * ROLLBACK，DB 不会停在「半替换」状态。
 *
 * **取消语义**：[inspect] / [export] / [import] 都由同一个互斥操作门保护。显式取消或普通
 * `Job.cancel()` 都会触发 Provider 的 [CancellationSignal] 并关闭已经创建的流。导入写入与归一化
 * 阶段仍可取消并回滚；全部写入完成后 [cancelActive] 不再取消所属 Job，让 Room 提交与图片权限
 * 收尾返回确定结果。父协程自身取消仍遵循结构化并发语义。
 *
 * **内存边界**：[export] 先把同一数据库快照编码到只计数不留存的输出流，确认结果可被本版本
 * 重新导入后才打开 SAF 目标；实际写入仍用 [encodeToStream]，不构造完整 JSON 字符串。
 * 导出前查询的数据列表会常驻内存。
 * [inspect] / [import] 直接从带硬上限的输入流解码，不先构造完整 JSON 字符串或 JSON 树。
 */
class BackupRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val cardDao: CardDao,
    private val folderDao: CardFolderDao,
    private val transactionDao: TransactionDao,
    private val normalizeInTransaction: suspend () -> Int,
    private val reconcileImagePermissions: suspend () -> Unit = {},
    private val maxBackupBytes: Long = DEFAULT_MAX_BACKUP_BYTES,
    private val openInputStream: (Uri, CancellationSignal) -> InputStream? = { uri, signal ->
        context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.createInputStream()
    },
    private val openOutputStream: (Uri, String, CancellationSignal) -> OutputStream? = { uri, mode, signal ->
        context.contentResolver.openAssetFileDescriptor(uri, mode, signal)?.createOutputStream()
    },
) {
    init {
        require(maxBackupBytes > 0L) { "maxBackupBytes 必须大于 0" }
    }

    private val json =
        Json {
            prettyPrint = true
            // 同一 schema 版本新增可选字段时，旧客户端仍可读取；破坏性变更必须提升 version。
            ignoreUnknownKeys = true
            encodeDefaults = true // 默认值字段也写出来，避免空备份看起来"什么都没有"
        }

    private val operationMutex = Mutex()

    @Volatile
    private var activeOperation: ActiveOperation? = null

    /**
     * 尝试取消当前文件操作，并主动关闭流以打断可能阻塞的 ContentProvider I/O。
     *
     * 返回 [BackupCancelResult.COMMIT_IN_PROGRESS] 时，调用方不得再直接取消所属协程，应等待
     * 成功或失败回执，避免数据库已替换却被 UI 当成静默取消。
     */
    fun cancelActive(): BackupCancelResult = activeOperation?.cancelIfPossible() ?: BackupCancelResult.NO_ACTIVE_OPERATION

    /**
     * 完整读取并校验备份，返回确认对话框所需摘要。
     *
     * 预览与真正导入复用同一解码和结构校验路径，避免 UI 对一个文件显示“可导入”，
     * 到仓库层却按另一套规则拒绝。
     */
    suspend fun inspect(uri: Uri): BackupFileInfo =
        runExclusive {
            val validated = readAndValidateBundle(uri)
            val bundle = validated.bundle
            BackupFileInfo(
                cardCount = bundle.cards.size,
                folderCount = bundle.folders.size,
                transactionCount = bundle.transactions.size,
                imageUriUserCount = countUserImageCards(bundle),
                lastModifiedMillis = queryLastModified(uri),
                contentSha256 = validated.contentSha256,
            )
        }

    /**
     * 导出：把数据库全量数据写到 [uri]，返回总行数（cards + folders + transactions）。
     *
     * 整个 I/O 在 [Dispatchers.IO] 上跑，UI 线程不阻塞。
     *
     * 同一快照先编码到限流计数 sink，确保输出不超过导入硬上限；预检通过前不会打开或截断
     * 用户选择的目标。实际序列化直接写入目标流，不额外构造完整 JSON 字符串。
     */
    suspend fun export(uri: Uri): ExportSummary =
        runExclusive {
            // 三张表必须来自同一个数据库快照，否则导出期间的一笔并发写入可能制造孤立外键。
            val bundle =
                database.withTransaction {
                    BackupBundle(
                        version = BackupBundle.SCHEMA_VERSION,
                        cards = cardDao.listAll().map { it.toBackup() },
                        folders = folderDao.listAll().map { it.toBackupV1() },
                        transactions = transactionDao.listAll().map { it.toBackupV1() },
                    )
                }
            try {
                val cancellationSignal = checkNotNull(activeOperation).cancellationSignal
                val operationJob = checkNotNull(currentCoroutineContext()[Job])
                // 使用同一个不可变快照和同一个 serializer 做无缓存预检，保证 App 不会导出一个
                // 自己因大小上限而永远无法导入的文件；预检失败时旧 SAF 目标保持原样。
                @OptIn(ExperimentalSerializationApi::class)
                json.encodeToStream(
                    BackupBundle.serializer(),
                    bundle,
                    SizeLimitedOutputStream(maxBackupBytes, operationJob, cancellationSignal),
                )
                cancellationSignal.throwIfCanceled()
                // ContentResolver 的 "w" 不保证截断旧内容；"wt" 才能安全覆盖较长的旧备份。
                val output =
                    openOutputStream(uri, "wt", cancellationSignal)
                        ?: throw BackupException(context.getString(R.string.backup_error_open_target_uri))
                useTracked(output) { out ->
                    // encodeToStream 避免为输出再构造一份完整 JSON 字符串。
                    @OptIn(ExperimentalSerializationApi::class)
                    json.encodeToStream(BackupBundle.serializer(), bundle, out)
                    out.flush()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupSizeLimitExceededException) {
                throw BackupException(
                    context.getString(
                        R.string.backup_error_file_too_large,
                        (maxBackupBytes + MEBIBYTE - 1L) / MEBIBYTE,
                    ),
                    e,
                )
            } catch (e: OperationCanceledException) {
                throw BackupException(
                    context.getString(
                        R.string.backup_error_write_failed,
                        e.message ?: context.getString(R.string.common_unknown_reason),
                    ),
                    e,
                )
            } catch (e: IOException) {
                throw BackupException(
                    context.getString(
                        R.string.backup_error_write_failed,
                        e.message ?: context.getString(R.string.common_unknown_reason),
                    ),
                    e,
                )
            }
            ExportSummary(
                cardCount = bundle.cards.size,
                folderCount = bundle.folders.size,
                transactionCount = bundle.transactions.size,
            )
        }

    /**
     * 导入：从 [uri] 读 JSON，按 [mode] 写入数据库。
     *
     * 返回 [ImportResult] 含实际插入条数 + 跳过 / 重名 / 卡面 URI 统计。
     * 失败抛 [BackupException]（UI 层 try-catch 转 Snackbar）。
     * [expectedContentSha256] 必须来自本次用户确认前的 [inspect]，仓库会重新读取并比对原始内容。
     * 显式取消在读取、写入与归一化阶段会回滚；提交边界后应等待事务与权限收尾的确定结果。
     */
    suspend fun import(
        uri: Uri,
        mode: ImportMode,
        expectedContentSha256: String,
    ): ImportResult =
        runExclusive {
            val validated = readAndValidateBundle(uri)
            if (validated.contentSha256 != expectedContentSha256) {
                throw BackupException(context.getString(R.string.backup_error_file_changed))
            }
            val bundle = validated.bundle

            // 关键：整个写库动作包在一个 Room 事务里。
            // DAO 写入与归一化仍可取消并 ROLLBACK；只有全部完成、即将交给 Room 提交时才原子切换
            // 到拒绝显式取消的阶段，避免大备份一开始写入就失去取消能力。
            val result =
                try {
                    database.withTransaction {
                        val importResult =
                            when (mode) {
                                ImportMode.REPLACE -> doReplace(bundle)
                                ImportMode.MERGE -> doMerge(bundle)
                            }
                        normalizeInTransaction()
                        enterCommitBoundary()
                        importResult
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    throw e
                } catch (e: Exception) {
                    throw BackupException(
                        context.getString(
                            R.string.backup_error_db_write_failed,
                            e.message ?: context.getString(R.string.common_unknown_error),
                        ),
                        e,
                    )
                }
            // 数据库已提交后只剩 best-effort 权限收尾；显式取消不能再制造“已导入但无回执”。
            withContext(NonCancellable) {
                try {
                    reconcileImagePermissions()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(BACKUP_LOG_TAG, "同步卡面图片权限失败（${e::class.java.simpleName}）")
                }
                result
            }
        }

    private suspend fun readAndValidateBundle(uri: Uri): ValidatedBackup {
        val validated = readBundle(uri)
        val bundle = validated.bundle
        currentCoroutineContext().ensureActive()
        if (bundle.version !in BackupBundle.MIN_SUPPORTED_SCHEMA_VERSION..BackupBundle.SCHEMA_VERSION) {
            throw BackupException(
                context.getString(
                    R.string.backup_error_version_mismatch,
                    bundle.version,
                    BackupBundle.SCHEMA_VERSION,
                ),
            )
        }
        if (!bundle.hasValidStructure()) {
            throw BackupException(context.getString(R.string.backup_error_invalid_structure))
        }
        return validated
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readBundle(uri: Uri): ValidatedBackup {
        try {
            val raw =
                openInputStream(uri, checkNotNull(activeOperation).cancellationSignal)
                    ?: throw BackupException(context.getString(R.string.backup_error_open_source_uri))
            val bounded = SizeLimitedInputStream(raw, maxBackupBytes)
            val digest = MessageDigest.getInstance(SHA_256)
            val input = PushbackInputStream(BufferedInputStream(DigestInputStream(bounded, digest)), 1)
            val bundle =
                useTracked(input) { tracked ->
                    skipWhitespaceOrThrowEmpty(tracked)
                    json.decodeFromStream(BackupBundle.serializer(), tracked)
                }
            return ValidatedBackup(bundle, digest.digest().toLowerHex())
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: BackupSizeLimitExceededException) {
            throw BackupException(
                context.getString(
                    R.string.backup_error_file_too_large,
                    (maxBackupBytes + MEBIBYTE - 1L) / MEBIBYTE,
                ),
                e,
            )
        } catch (e: FileNotFoundException) {
            throw BackupException(
                context.getString(
                    R.string.backup_error_source_missing,
                    e.message ?: context.getString(R.string.common_unknown_reason),
                ),
                e,
            )
        } catch (e: SerializationException) {
            throw BackupException(context.getString(R.string.backup_error_malformed_json), e)
        } catch (e: OperationCanceledException) {
            throw BackupException(
                context.getString(
                    R.string.backup_error_read_failed,
                    e.message ?: context.getString(R.string.common_unknown_reason),
                ),
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw BackupException(context.getString(R.string.backup_error_malformed_json), e)
        } catch (e: IOException) {
            throw BackupException(
                context.getString(
                    R.string.backup_error_read_failed,
                    e.message ?: context.getString(R.string.common_unknown_reason),
                ),
                e,
            )
        }
    }

    private suspend fun queryLastModified(uri: Uri): Long? =
        try {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null,
                    null,
                    null,
                    checkNotNull(activeOperation).cancellationSignal,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: OperationCanceledException) {
            checkNotNull(activeOperation).ensureCallerActive()
            null
        } catch (_: Exception) {
            checkNotNull(activeOperation).ensureCallerActive()
            null
        }

    private suspend fun <T> runExclusive(block: suspend () -> T): T {
        if (!operationMutex.tryLock()) {
            throw BackupException(context.getString(R.string.backup_error_operation_busy))
        }
        // 在第一次切到 IO 线程前登记当前 Job，确保 UI 刚显示“处理中”就点取消也不会漏掉。
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
                // 关闭阻塞流后，I/O 子上下文可能短暂仍为 active；最初 caller Job 才是取消真源。
                operation.ensureCallerActive()
                throw e
            }
        } finally {
            operation.closeResource()
            if (activeOperation === operation) activeOperation = null
            operationMutex.unlock()
        }
    }

    /** 写入与归一化完成后原子进入提交阶段；若显式取消先赢得竞态，Room 会回滚本次事务。 */
    private suspend fun enterCommitBoundary() {
        currentCoroutineContext().ensureActive()
        if (!checkNotNull(activeOperation).beginCommit()) {
            throw CancellationException("Backup operation cancelled before commit")
        }
        currentCoroutineContext().ensureActive()
    }

    private inline fun <T : Closeable, R> useTracked(
        resource: T,
        block: (T) -> R,
    ): R {
        val operation = checkNotNull(activeOperation)
        operation.register(resource)
        return try {
            block(resource)
        } finally {
            operation.release(resource)
        }
    }

    /** 跳过 JSON 允许的前导空白；纯空白文件与 0 字节文件统一按“空备份”处理。 */
    private fun skipWhitespaceOrThrowEmpty(input: PushbackInputStream) {
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                throw BackupException(context.getString(R.string.backup_error_empty_file))
            }
            if (byte !in JSON_WHITESPACE_BYTES) {
                input.unread(byte)
                return
            }
        }
    }

    /** 只统计真正保存了 URI 的用户卡面；旧数据虽可能默认为 USER，但空 URI 不需要提醒。 */
    private fun countUserImageCards(bundle: BackupBundle): Int =
        bundle.cards.count { card ->
            card.imageSourceType == ImageSourceType.USER.key && !card.imageUri.isNullOrBlank()
        }

    /**
     * REPLACE 模式：先清空（含 CASCADE），再按依赖顺序写入。
     *
     * 备份主键只作为文件内部的引用标签；导入时统一让 SQLite 分配新主键并重写外键。
     * 这样既不依赖历史数据库序列，也避免恶意极大主键耗尽 AUTOINCREMENT 空间。
     * 不在 bundle.folders 中的 folder 引用置为 null，并计入结果。
     */
    private suspend fun doReplace(bundle: BackupBundle): ImportResult {
        // 1) 删 cards → 自动 CASCADE 清 transactions
        cardDao.deleteAll()
        // 2) 删 folders
        folderDao.deleteAll()
        // 3) 按依赖顺序写，并只在本次备份内部重映射引用。
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
                    card.toEntity(bundle.version).copy(id = 0L, folderId = safeFolderId),
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
            imageUriUserCount = countUserImageCards(bundle),
        )
    }

    /**
     * MERGE 模式：保留现有数据，把备份追加进去。
     *
     * 与现库重名的 folder / card 仍会保留，数量通过
     * [ImportResult.duplicateFolderNames] / [ImportResult.duplicateCardNames] 返回给 UI。
     *
     * 孤立 transaction（cardId 在备份卡片中找不到映射）被跳过不写入，
     * 计入 [ImportResult.transactionsSkipped]，UI 提示用户「其中 N 笔因引用不存在的卡被跳过」。
     *
     * `Cards.folder_id` 是外键引用 `card_folders.id`，MERGE 写库时必须
     * 保证 `card.folderId` 只能指向 backup 自身包含且已重映射的 folder。
     * 不能把 backup 中缺失的 folder id 猜成现库恰好同号的 folder；主键只在各自数据集内有意义。
     * 找不到映射的引用会置为 null，避免误归类或外键失败。
     *
     * 校验语义跟 [doReplace] 对齐：folderId 不在合法集合里 → 置 null + 计入
     * [ImportResult.cardsSkippedInvalidFolder]，不抛异常、不回滚。
     *
     * **MERGE 永远走 INSERT 路径**（`id = 0L` + AUTOINCREMENT），**不**会覆盖现库同 id 的 folder / card。
     *
     * 内部用 `folderRemap` / `cardRemap` 维护 old → new 映射，仅供
     * doMerge 重写外键时使用。
     */
    private suspend fun doMerge(bundle: BackupBundle): ImportResult {
        // 1) **写之前**先抓现有 name 集合——重名检测不能包含本次刚追加的。
        val existingFolders = folderDao.listAll()
        val existingFolderNames = existingFolders.map { it.name }.toSet()
        val existingCardNames = cardDao.listAll().map { it.name }.toSet()

        // 2) 写 folders：id 清零让 SQLite 重新分配，记 oldFolderId → newFolderId 映射
        val folderRemap = mutableMapOf<Long, Long>()
        bundle.folders.forEach { folder ->
            val newId = folderDao.insert(folder.toEntity().copy(id = 0L))
            folderRemap[folder.id] = newId
        }
        // 3) 写 cards：id 清零，folderId 用映射后的新 id。
        //    backup 未同时携带目标 folder 时不能猜测现库同号 id，必须置 null。
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
                    card.toEntity(bundle.version).copy(id = 0L, folderId = safeFolderId),
                )
            cardRemap[card.id] = newId
        }

        // 4) 写 transactions：id 清零，cardId 用映射后的新 id（必须找到映射；找不到的跳过）
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

        // 5) 重名检测：跟"写之前"抓的现库 name 集合对比，**不包含本次刚追加的**
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
            imageUriUserCount = countUserImageCards(bundle),
        )
    }
}

private const val MEBIBYTE: Long = 1024L * 1024L
private const val SHA_256 = "SHA-256"
private const val BACKUP_LOG_TAG = "BackupRepository"
private val JSON_WHITESPACE_BYTES = setOf(' '.code, '\t'.code, '\r'.code, '\n'.code)

private data class ValidatedBackup(
    val bundle: BackupBundle,
    val contentSha256: String,
)

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

/**
 * 只验证导入前必须成立、且不能靠归一化安全修复的结构不变量。
 * 缺失 folder/card 外键仍由 REPLACE/MERGE 各自既有语义处理。
 */
private fun BackupBundle.hasValidStructure(): Boolean {
    fun List<Long>.containsUniquePositiveIds(): Boolean = all { it > 0L } && distinct().size == size

    return folders.map { it.id }.containsUniquePositiveIds() &&
        cards.map { it.id }.containsUniquePositiveIds() &&
        transactions.map { it.id }.containsUniquePositiveIds() &&
        cards.all { card ->
            val parsedType = card.cardType?.let(CardType::fromKeyOrNull)
            val hasValidType =
                when {
                    card.cardType != null && parsedType == null -> false
                    version == BackupBundle.SCHEMA_VERSION -> parsedType != null
                    else -> true
                }
            card.requiredCount > 0 &&
                (card.folderId == null || card.folderId > 0L) &&
                hasValidType &&
                (card.statementDay == null || card.statementDay.isValidCardMonthDay()) &&
                (card.repaymentDay == null || card.repaymentDay.isValidCardMonthDay())
        } &&
        transactions.all { it.cardId > 0L }
}

/** 每次操作独占一个实例，避免取消旧操作时误关掉随后启动的新操作。 */
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

    /** 提交边界前能赢得取消；提交阶段必须完成并给调用方确定结果。 */
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

/** Provider 的 close 失败不应覆盖原文件操作异常，但协程取消必须保持为取消。 */
private fun Closeable.closeBestEffort() {
    try {
        close()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 文件流已经从 active slot 移除；普通 close 失败没有可恢复动作。
    }
}

private enum class OperationPhase {
    CANCELLABLE,
    COMMITTING,
    CANCELLED,
}

private class BackupSizeLimitExceededException : IOException()

/**
 * 只计数、不保留内容的导出预检 sink。它与导入共用同一个字节上限，并在序列化期间响应取消。
 */
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
        if (byteCount > maxBytes - bytesWritten) throw BackupSizeLimitExceededException()
        bytesWritten += byteCount
    }
}

/**
 * 无论 ContentProvider 是否报告文件长度，都把最大读取量作为不可绕过的硬边界。
 * 读取恰好达到上限时会再探测一个字节，以区分“刚好等于上限”和“超过上限”。
 */
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
            throw BackupSizeLimitExceededException()
        }
}

/**
 * 导出结果摘要，按卡片、文件夹和流水分类提供给 UI。
 */
data class ExportSummary(
    val cardCount: Int,
    val folderCount: Int,
    val transactionCount: Int,
) {
    val total: Int get() = cardCount + folderCount + transactionCount

    val isEmpty: Boolean get() = total == 0
}
