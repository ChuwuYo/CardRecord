package com.shuaji.cards.data

import android.content.ContentResolver
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.ImageSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 用户卡面 URI 授权的唯一所有者；数据库引用和编辑中的临时选择共同决定授权是否保留。 */
interface UserImagePermissionStore {
    suspend fun acquire(uri: String): Boolean

    suspend fun releasePending(uris: Set<String>)

    suspend fun reconcile()
}

/** 测试与无 Android URI 环境使用；生产容器必须注入 [ContentResolverUserImagePermissionStore]。 */
object NoOpUserImagePermissionStore : UserImagePermissionStore {
    override suspend fun acquire(uri: String): Boolean = true

    override suspend fun releasePending(uris: Set<String>) = Unit

    override suspend fun reconcile() = Unit
}

/**
 * 统一管理 `OpenDocument` 的持久化读取授权。
 *
 * 新选 URI 在表单保存或放弃前按持有者计数，因此其他写操作触发清理时不会误释放。
 * 进程重启后 pending 自然清空，启动 reconcile 会回收“选图后被杀进程”等遗留授权。
 */
class ContentResolverUserImagePermissionStore(
    private val contentResolver: ContentResolver,
    private val cardDao: CardDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserImagePermissionStore {
    private val mutex = Mutex()
    private val pendingUriOwners = mutableMapOf<String, Int>()

    override suspend fun acquire(uri: String): Boolean =
        // take + pending 登记必须原子完成；调用方取消后会在 finally 结束这份临时租约。
        withContext(NonCancellable + dispatcher) {
            mutex.withLock {
                pendingUriOwners[uri]?.let { owners ->
                    pendingUriOwners[uri] = owners + 1
                    return@withLock true
                }
                try {
                    contentResolver.takePersistableUriPermission(
                        uri.toUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    pendingUriOwners[uri] = 1
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "无法持久化卡面图片权限（${error::class.java.simpleName}）")
                    false
                }
            }
        }

    override suspend fun releasePending(uris: Set<String>) {
        withContext(dispatcher) {
            mutex.withLock {
                uris.forEach { uri ->
                    val owners = pendingUriOwners[uri] ?: return@forEach
                    if (owners == 1) pendingUriOwners.remove(uri) else pendingUriOwners[uri] = owners - 1
                }
                reconcileLockedSafely()
            }
        }
    }

    override suspend fun reconcile() {
        withContext(dispatcher) {
            mutex.withLock { reconcileLockedSafely() }
        }
    }

    private suspend fun reconcileLockedSafely() {
        try {
            reconcileLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "同步卡面图片权限失败（${error::class.java.simpleName}）")
        }
    }

    private suspend fun reconcileLocked() {
        val referencedUris =
            cardDao
                .listAll()
                .asSequence()
                .filter { it.imageSourceType == ImageSourceType.USER.key }
                .mapNotNull { it.imageUri?.takeIf(String::isNotBlank) }
                .toSet()
        val retainedUris = referencedUris + pendingUriOwners.keys
        try {
            contentResolver.persistedUriPermissions
                .asSequence()
                .filter { it.isReadPermission && it.uri.toString() !in retainedUris }
                .forEach { permission ->
                    try {
                        contentResolver.releasePersistableUriPermission(
                            permission.uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "释放无引用卡面图片权限失败（${error::class.java.simpleName}）")
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "读取持久化卡面图片权限失败（${error::class.java.simpleName}）")
        }
    }

    private companion object {
        const val TAG = "UserImagePermission"
    }
}
