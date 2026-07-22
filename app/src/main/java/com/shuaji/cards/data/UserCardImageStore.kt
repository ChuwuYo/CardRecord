package com.shuaji.cards.data

import android.content.ContentResolver
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.core.net.toUri
import com.shuaji.cards.data.local.CardDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** 内容寻址的用户卡面资源 ID；格式固定为小写 SHA-256，不把文件路径带入业务模型。 */
@JvmInline
value class ImageAssetId private constructor(
    val value: String,
) {
    companion object {
        private val FORMAT = Regex("[0-9a-f]{64}")

        fun parse(value: String?): ImageAssetId? = value?.takeIf(FORMAT::matches)?.let(::ImageAssetId)

        internal fun fromDigest(digest: ByteArray): ImageAssetId = ImageAssetId(digest.toLowerHex())
    }
}

/** 一次暂存租约。租约保护文件不被回收；保存或取消后必须显式释放。 */
data class StagedUserImage(
    val assetId: ImageAssetId,
    val leaseToken: String,
    val displayUri: String,
)

/** 用户图片的深模块边界：外部 URI 只作为输入，调用方只持有稳定资产 ID。 */
interface UserCardImageStore {
    suspend fun stageFromUri(uri: String): StagedUserImage

    suspend fun stageFromBackup(
        expectedAssetId: ImageAssetId,
        input: InputStream,
        maxBytes: Long,
    ): StagedUserImage

    /** 与正式导入复用同一套字节、哈希和图片格式校验，但不提升为持久资产。 */
    suspend fun validateBackupImage(
        expectedAssetId: ImageAssetId,
        input: InputStream,
        maxBytes: Long,
    )

    /** 同步结束租约；文件回收由随后或下次 [collectGarbage] 完成，供 ViewModel 清理时也能可靠调用。 */
    fun releaseLeases(images: Set<StagedUserImage>)

    /** 只在启动和导出前尝试把历史外部 URI 转成私有资产。 */
    suspend fun migrateLegacyImages()

    /** 只访问数据库与应用私有目录，不触碰外部 Provider。 */
    suspend fun collectGarbage()

    fun resolve(assetId: ImageAssetId): String

    fun openAsset(assetId: ImageAssetId): InputStream

    fun assetSize(assetId: ImageAssetId): Long
}

class UserImageImportException(
    cause: Throwable? = null,
) : IOException(cause)

class UserImageMissingException : IOException()

/**
 * 把用户选择的图片复制到内部 `filesDir`，并以内容哈希作为不可变文件名。
 *
 * 文件系统和 SQLite 不能共享事务，因此先完整写入并原子提升文件，再让数据库引用它；
 * 数据库写入失败只会留下可回收孤儿，不会留下指向半文件的记录。
 */
class ContentResolverUserCardImageStore(
    private val contentResolver: ContentResolver,
    private val cardDao: CardDao,
    private val rootDirectory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openSource: (String, CancellationSignal) -> InputStream? = { uri, signal ->
        contentResolver.openAssetFileDescriptor(uri.toUri(), "r", signal)?.createInputStream()
    },
    private val validateSource: (File) -> Boolean = ::hasValidImageBounds,
    private val maxSelectionBytes: Long = DEFAULT_MAX_USER_IMAGE_BYTES,
) : UserCardImageStore {
    private val assetMutex = Mutex()
    private val migrationMutex = Mutex()
    private val pendingLeases = ConcurrentHashMap<String, ImageAssetId>()
    private val activeStagingFiles = ConcurrentHashMap.newKeySet<String>()
    private val assetsDirectory = rootDirectory.resolve(ASSETS_DIRECTORY)
    private val stagingDirectory = rootDirectory.resolve(STAGING_DIRECTORY)

    init {
        require(maxSelectionBytes > 0L) { "maxSelectionBytes 必须大于 0" }
    }

    override suspend fun stageFromUri(uri: String): StagedUserImage = stageWithLease { prepareFromUri(uri) }

    override suspend fun stageFromBackup(
        expectedAssetId: ImageAssetId,
        input: InputStream,
        maxBytes: Long,
    ): StagedUserImage =
        stageWithLease {
            prepareAsset(
                input = input,
                expectedAssetId = expectedAssetId,
                maxBytes = maxBytes,
                job = checkNotNull(currentCoroutineContext()[Job]),
                cancellationSignal = null,
                durability = TemporaryImageDurability.PROMOTABLE,
            )
        }

    override suspend fun validateBackupImage(
        expectedAssetId: ImageAssetId,
        input: InputStream,
        maxBytes: Long,
    ) = withContext(dispatcher) {
        val prepared =
            prepareAsset(
                input = input,
                expectedAssetId = expectedAssetId,
                maxBytes = maxBytes,
                job = checkNotNull(currentCoroutineContext()[Job]),
                cancellationSignal = null,
                durability = TemporaryImageDurability.VALIDATION_ONLY,
            )
        try {
            Unit
        } finally {
            discardPrepared(prepared)
        }
    }

    override fun releaseLeases(images: Set<StagedUserImage>) {
        images.forEach { staged ->
            pendingLeases.remove(staged.leaseToken, staged.assetId)
        }
    }

    override suspend fun migrateLegacyImages() {
        withContext(dispatcher) {
            migrationMutex.withLock {
                migrateLegacyUris()
                val cards = cardDao.listAll()
                releaseUnreferencedLegacyPermissions(cards.mapNotNull { it.imageUri }.toSet())
            }
        }
    }

    override suspend fun collectGarbage() {
        withContext(dispatcher) {
            assetMutex.withLock {
                // 必须先快照租约再读数据库：租约只会在数据库提交后释放。反过来读取会让
                // “旧 DB 快照 + 已释放租约”错误删除刚提交引用的文件。
                val leasedAssets = pendingLeases.values.toSet()
                reconcileFilesLocked(cardDao.listAll(), leasedAssets)
            }
        }
    }

    /** 数据库只会引用已完整提升的文件；这里保持纯映射，避免在 UI Flow 中同步访问文件系统。 */
    override fun resolve(assetId: ImageAssetId): String = Uri.fromFile(assetFile(assetId)).toString()

    override fun openAsset(assetId: ImageAssetId): InputStream =
        assetFile(assetId).takeIf(File::isFile)?.inputStream() ?: throw UserImageMissingException()

    override fun assetSize(assetId: ImageAssetId): Long =
        assetFile(assetId).takeIf(File::isFile)?.length() ?: throw UserImageMissingException()

    /**
     * `withContext` 在返回调度器时有及时取消保证：复制可能已经成功，但结果租约不再交给调用方。
     * 在存储边界记录结果并处理该竞态，UI 无需把整个图片复制放进 NonCancellable。
     */
    private suspend fun stageWithLease(prepare: suspend () -> PreparedAsset): StagedUserImage {
        var completed: StagedUserImage? = null
        return try {
            withContext(dispatcher) {
                // 准备与提升共用同一个调度边界，确保 prompt cancellation 丢弃返回值时，
                // 外层仍持有临时文件或已登记租约并能完成清理。
                val prepared = prepare()
                try {
                    assetMutex.withLock {
                        promotePreparedLocked(prepared).also { completed = it }
                    }
                } finally {
                    discardPrepared(prepared)
                }
            }
        } catch (cancelled: CancellationException) {
            completed?.let { staged -> releaseCancelledLease(staged, cancelled) }
            throw cancelled
        }
    }

    private suspend fun releaseCancelledLease(
        staged: StagedUserImage,
        cancellation: CancellationException,
    ) {
        try {
            withContext(NonCancellable) {
                releaseLeases(setOf(staged))
                collectGarbage()
            }
        } catch (cleanupCancelled: CancellationException) {
            cancellation.addSuppressed(cleanupCancelled)
        } catch (error: Exception) {
            Log.w(TAG, "回收已取消卡面图片失败（${error::class.java.simpleName}）")
        }
    }

    /**
     * Provider I/O 可能阻塞在 open/read；把来源流和 CancellationSignal 绑定到协程取消，
     * 即使工作线程没有机会再次调度，也能从取消线程主动打断读取。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun prepareFromUri(uri: String): PreparedAsset =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val sourceReference = AtomicReference<InputStream?>()
            continuation.invokeOnCancellation {
                try {
                    cancellationSignal.cancel()
                } catch (_: Exception) {
                    // 取消回调不得抛出；关闭来源流仍可打断大多数阻塞读取。
                } finally {
                    closeBestEffort(sourceReference.getAndSet(null))
                }
            }
            var preparedAsset: PreparedAsset? = null
            var handedOff = false
            try {
                val source = openSource(uri, cancellationSignal) ?: throw UserImageImportException()
                sourceReference.set(source)
                if (!continuation.isActive) {
                    return@suspendCancellableCoroutine
                }
                preparedAsset =
                    prepareAsset(
                        input = source,
                        expectedAssetId = null,
                        maxBytes = maxSelectionBytes,
                        job = checkNotNull(continuation.context[Job]),
                        cancellationSignal = cancellationSignal,
                        durability = TemporaryImageDurability.PROMOTABLE,
                    )
                val prepared = checkNotNull(preparedAsset)
                if (continuation.isActive) {
                    continuation.resume(prepared) { _, value, _ -> discardPrepared(value) }
                    handedOff = true
                }
            } catch (error: Exception) {
                if (continuation.isActive) {
                    val failure =
                        when (error) {
                            is CancellationException, is UserImageImportException -> error
                            else -> UserImageImportException(error)
                        }
                    continuation.resumeWith(Result.failure(failure))
                }
            } finally {
                closeBestEffort(sourceReference.getAndSet(null))
                if (!handedOff) preparedAsset?.let(::discardPrepared)
            }
        }

    private fun prepareAsset(
        input: InputStream,
        expectedAssetId: ImageAssetId?,
        maxBytes: Long,
        job: Job,
        cancellationSignal: CancellationSignal?,
        durability: TemporaryImageDurability,
    ): PreparedAsset {
        require(maxBytes > 0L) { "maxBytes 必须大于 0" }
        ensureDirectories()
        val temporaryFile = stagingDirectory.resolve("${UUID.randomUUID()}$PART_SUFFIX")
        activeStagingFiles += temporaryFile.name
        var completed = false
        return try {
            val digest = MessageDigest.getInstance(IMAGE_SHA_256)
            var total = 0L
            FileOutputStream(temporaryFile).use { output ->
                val buffer = ByteArray(IMAGE_COPY_BUFFER_BYTES)
                while (true) {
                    job.ensureActive()
                    cancellationSignal?.throwIfCanceled()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    if (count.toLong() > maxBytes - total) throw UserImageImportException()
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    total += count
                }
                if (total == 0L) throw UserImageImportException()
                if (durability == TemporaryImageDurability.PROMOTABLE) output.fd.sync()
            }
            if (!validateSource(temporaryFile)) throw UserImageImportException()
            val assetId = ImageAssetId.fromDigest(digest.digest())
            if (expectedAssetId != null && expectedAssetId != assetId) throw UserImageImportException()
            PreparedAsset(temporaryFile, assetId).also { completed = true }
        } catch (error: Exception) {
            when (error) {
                is CancellationException, is OperationCanceledException, is UserImageImportException -> throw error
                else -> throw UserImageImportException(error)
            }
        } finally {
            if (!completed) discardPreparedFile(temporaryFile)
        }
    }

    private fun promotePreparedLocked(prepared: PreparedAsset): StagedUserImage {
        val destination = assetFile(prepared.assetId)
        // prepared 已按同一内容 ID 校验并落盘；直接原子替换既修复潜在损坏文件，
        // 也避免在全局资产锁内再次读取整张图片。
        moveAtomically(prepared.file, destination)
        val token = UUID.randomUUID().toString()
        pendingLeases[token] = prepared.assetId
        return StagedUserImage(
            assetId = prepared.assetId,
            leaseToken = token,
            displayUri = Uri.fromFile(destination).toString(),
        )
    }

    private suspend fun migrateLegacyUris() {
        cardDao
            .listAll()
            .asSequence()
            .filter { it.imageAssetId.isNullOrBlank() && !it.imageUri.isNullOrBlank() }
            .forEach { card ->
                var staged: StagedUserImage? = null
                try {
                    val uri = checkNotNull(card.imageUri)
                    staged = stageFromUri(uri)
                    cardDao.adoptOwnedImage(
                        cardId = card.id,
                        legacyUri = uri,
                        imageAssetId = staged.assetId.value,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "迁移旧卡面图片失败（${error::class.java.simpleName}）")
                } finally {
                    staged?.let { releaseLeases(setOf(it)) }
                }
            }
    }

    private fun reconcileFilesLocked(
        cards: List<com.shuaji.cards.data.local.CardEntity>,
        leasedAssets: Set<ImageAssetId>,
    ) {
        val retained = cards.mapNotNull { ImageAssetId.parse(it.imageAssetId) }.toSet() + leasedAssets
        assetsDirectory.listFiles().orEmpty().forEach { file ->
            val id =
                file.name
                    .takeIf { it.endsWith(ASSET_SUFFIX) }
                    ?.removeSuffix(ASSET_SUFFIX)
                    ?.let(ImageAssetId::parse)
            if (!file.isFile || id == null || id !in retained) file.delete()
        }
        stagingDirectory.listFiles().orEmpty().forEach { file ->
            if (file.name !in activeStagingFiles) file.delete()
        }
    }

    private fun releaseUnreferencedLegacyPermissions(referencedUris: Set<String>) {
        try {
            contentResolver.persistedUriPermissions
                .orEmpty()
                .asSequence()
                .filter { it.isReadPermission && it.uri.toString() !in referencedUris }
                .forEach { permission ->
                    try {
                        contentResolver.releasePersistableUriPermission(
                            permission.uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "释放旧卡面图片权限失败（${error::class.java.simpleName}）")
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "读取旧卡面图片权限失败（${error::class.java.simpleName}）")
        }
    }

    private fun ensureDirectories() {
        ensureDirectory(assetsDirectory)
        ensureDirectory(stagingDirectory)
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw UserImageImportException()
    }

    private fun discardPrepared(prepared: PreparedAsset) = discardPreparedFile(prepared.file)

    private fun discardPreparedFile(file: File) {
        activeStagingFiles -= file.name
        file.delete()
    }

    private fun moveAtomically(
        source: File,
        destination: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun assetFile(assetId: ImageAssetId): File = assetsDirectory.resolve("${assetId.value}$ASSET_SUFFIX")

    private data class PreparedAsset(
        val file: File,
        val assetId: ImageAssetId,
    )

    private enum class TemporaryImageDurability {
        /** 临时文件会原子提升为持久资产，提升前必须落盘。 */
        PROMOTABLE,

        /** 文件仅用于本次格式校验并立即删除，无需强制闪存同步。 */
        VALIDATION_ONLY,
    }

    private companion object {
        const val TAG = "UserCardImageStore"
        const val ASSETS_DIRECTORY = "assets"
        const val STAGING_DIRECTORY = "staging"
        const val ASSET_SUFFIX = ".image"
        const val PART_SUFFIX = ".part"
    }
}

internal const val DEFAULT_MAX_USER_IMAGE_BYTES: Long = 32L * 1024L * 1024L

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

private fun hasValidImageBounds(file: File): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    return bounds.outWidth in 1..MAX_IMAGE_DIMENSION &&
        bounds.outHeight in 1..MAX_IMAGE_DIMENSION &&
        !bounds.outMimeType.isNullOrBlank()
}

private const val MAX_IMAGE_DIMENSION = 16_384
private const val IMAGE_SHA_256 = "SHA-256"
private const val IMAGE_COPY_BUFFER_BYTES = 32 * 1024

private fun closeBestEffort(input: InputStream?) {
    try {
        input?.close()
    } catch (_: Exception) {
        // 取消路径只负责打断读取；关闭错误不能覆盖原始取消或导入错误。
    }
}
