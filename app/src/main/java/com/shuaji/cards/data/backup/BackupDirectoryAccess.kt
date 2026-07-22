package com.shuaji.cards.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * 目录备份的存储边界。业务层只认识固定布局，不依赖 SAF 文档 URI 的层级或真实文件路径。
 */
internal interface BackupDirectoryAccess {
    fun createBackup(
        parentUri: Uri,
        suggestedName: String,
        cancellationSignal: CancellationSignal,
    ): BackupDirectory

    fun openBackup(
        directoryUri: Uri,
        cancellationSignal: CancellationSignal,
    ): BackupDirectory
}

/** 一个已经定位好的备份目录；所有文件名均由实现内部固定生成或精确匹配。 */
internal interface BackupDirectory {
    val displayName: String
    val lastModifiedMillis: Long?

    fun openManifestInput(cancellationSignal: CancellationSignal): InputStream?

    fun openManifestOutput(cancellationSignal: CancellationSignal): OutputStream?

    /** 清单解析后，仅索引它实际引用的图片；额外目录项不得进入长期内存。 */
    fun indexImageInputs(
        assetFileNames: Set<String>,
        cancellationSignal: CancellationSignal,
    )

    fun openImageInput(
        assetFileName: String,
        cancellationSignal: CancellationSignal,
    ): InputStream?

    fun openImageOutput(
        assetFileName: String,
        cancellationSignal: CancellationSignal,
    ): OutputStream?

    /** 只用于回收本次失败导出刚创建的不完整目录。 */
    fun delete(): Boolean
}

internal class AndroidBackupDirectoryAccess(
    context: Context,
    private val maxUnrelatedDirectoryEntries: Int = DEFAULT_MAX_UNRELATED_DIRECTORY_ENTRIES,
) : BackupDirectoryAccess {
    private val resolver = context.contentResolver

    init {
        require(maxUnrelatedDirectoryEntries >= 0) { "maxUnrelatedDirectoryEntries 不能为负数" }
    }

    override fun createBackup(
        parentUri: Uri,
        suggestedName: String,
        cancellationSignal: CancellationSignal,
    ): BackupDirectory {
        if (parentUri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw FileNotFoundException("A content tree URI is required")
        }
        return createSafBackup(parentUri, suggestedName, cancellationSignal)
    }

    override fun openBackup(
        directoryUri: Uri,
        cancellationSignal: CancellationSignal,
    ): BackupDirectory {
        if (directoryUri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw FileNotFoundException("A content tree URI is required")
        }
        return openSafBackup(directoryUri, cancellationSignal)
    }

    private fun createSafBackup(
        parentTreeUri: Uri,
        suggestedName: String,
        cancellationSignal: CancellationSignal,
    ): BackupDirectory {
        cancellationSignal.throwIfCanceled()
        val parentDocumentUri = parentTreeUri.asTreeDocumentUri()
        val backupUri =
            DocumentsContract.createDocument(
                resolver,
                parentDocumentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                suggestedName,
            ) ?: throw FileNotFoundException("Cannot create backup directory")
        return try {
            cancellationSignal.throwIfCanceled()
            val actualName = queryDocumentName(resolver, backupUri, cancellationSignal) ?: suggestedName
            SafBackupDirectory(
                resolver = resolver,
                treeUri = parentTreeUri,
                directoryUri = backupUri,
                displayName = actualName,
                manifestUri = null,
                imagesDirectoryUri = null,
                maxUnrelatedDirectoryEntries = maxUnrelatedDirectoryEntries,
                lastModifiedMillis = null,
            )
        } catch (cancelled: CancellationException) {
            deleteDocumentBestEffort(backupUri)
            throw cancelled
        } catch (error: Exception) {
            deleteDocumentBestEffort(backupUri)
            throw error
        }
    }

    private fun deleteDocumentBestEffort(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 原始创建失败更有诊断价值；残留目录没有最终 JSON，不会被识别为完整备份。
        }
    }

    private fun openSafBackup(
        treeUri: Uri,
        cancellationSignal: CancellationSignal,
    ): BackupDirectory {
        val directoryUri = treeUri.asTreeDocumentUri()
        val children =
            queryRelevantChildren(
                resolver = resolver,
                treeUri = treeUri,
                parentDocumentUri = directoryUri,
                acceptedNames = setOf(BACKUP_MANIFEST_FILE_NAME, BACKUP_IMAGES_DIRECTORY_NAME),
                maxUnrelatedEntries = maxUnrelatedDirectoryEntries,
                cancellationSignal = cancellationSignal,
            )
        val manifests = children.filter { it.name == BACKUP_MANIFEST_FILE_NAME && !it.isDirectory }
        if (manifests.size != 1) throw FileNotFoundException("Backup manifest is missing or ambiguous")
        val imageDirectories = children.filter { it.name == BACKUP_IMAGES_DIRECTORY_NAME && it.isDirectory }
        if (imageDirectories.size > 1) throw FileNotFoundException("Backup image directory is ambiguous")
        val imageDirectory = imageDirectories.singleOrNull()
        val displayName = queryDocumentName(resolver, directoryUri, cancellationSignal) ?: ""
        return SafBackupDirectory(
            resolver = resolver,
            treeUri = treeUri,
            directoryUri = directoryUri,
            displayName = displayName,
            manifestUri = manifests.single().uri,
            imagesDirectoryUri = imageDirectory?.uri,
            maxUnrelatedDirectoryEntries = maxUnrelatedDirectoryEntries,
            lastModifiedMillis = manifests.single().lastModifiedMillis,
        )
    }
}

private class SafBackupDirectory(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val directoryUri: Uri,
    override val displayName: String,
    private var manifestUri: Uri?,
    private var imagesDirectoryUri: Uri?,
    private val maxUnrelatedDirectoryEntries: Int,
    override val lastModifiedMillis: Long?,
) : BackupDirectory {
    private var imageUris: Map<String, Uri> = emptyMap()

    override fun openManifestInput(cancellationSignal: CancellationSignal): InputStream? =
        manifestUri?.let { resolver.openAssetFileDescriptor(it, "r", cancellationSignal)?.createInputStream() }

    override fun openManifestOutput(cancellationSignal: CancellationSignal): OutputStream? {
        cancellationSignal.throwIfCanceled()
        val uri =
            manifestUri ?: createExactDocument(
                resolver = resolver,
                parentUri = directoryUri,
                mimeType = JSON_MIME_TYPE,
                expectedName = BACKUP_MANIFEST_FILE_NAME,
                cancellationSignal = cancellationSignal,
            )?.also { manifestUri = it }
        return uri?.let { resolver.openAssetFileDescriptor(it, "wt", cancellationSignal)?.createOutputStream() }
    }

    override fun indexImageInputs(
        assetFileNames: Set<String>,
        cancellationSignal: CancellationSignal,
    ) {
        cancellationSignal.throwIfCanceled()
        if (assetFileNames.isEmpty()) {
            imageUris = emptyMap()
            return
        }
        val parent = imagesDirectoryUri ?: throw FileNotFoundException("Backup image directory is missing")
        val entries =
            queryRelevantChildren(
                resolver = resolver,
                treeUri = treeUri,
                parentDocumentUri = parent,
                acceptedNames = assetFileNames,
                maxUnrelatedEntries = maxUnrelatedDirectoryEntries,
                cancellationSignal = cancellationSignal,
            )
        val grouped = entries.filterNot(DocumentEntry::isDirectory).groupBy(DocumentEntry::name)
        imageUris =
            assetFileNames.associateWith { fileName ->
                val matches = grouped[fileName].orEmpty()
                if (matches.size != 1) {
                    throw FileNotFoundException("Backup image is missing or ambiguous: $fileName")
                }
                matches.single().uri
            }
    }

    override fun openImageInput(
        assetFileName: String,
        cancellationSignal: CancellationSignal,
    ): InputStream? =
        imageUris[assetFileName]?.let {
            resolver.openAssetFileDescriptor(it, "r", cancellationSignal)?.createInputStream()
        }

    override fun openImageOutput(
        assetFileName: String,
        cancellationSignal: CancellationSignal,
    ): OutputStream? {
        cancellationSignal.throwIfCanceled()
        val parent =
            imagesDirectoryUri ?: createExactDocument(
                resolver = resolver,
                parentUri = directoryUri,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                expectedName = BACKUP_IMAGES_DIRECTORY_NAME,
                cancellationSignal = cancellationSignal,
            )?.also { imagesDirectoryUri = it }
                ?: return null
        val uri =
            createExactDocument(
                resolver = resolver,
                parentUri = parent,
                mimeType = BINARY_MIME_TYPE,
                expectedName = assetFileName,
                cancellationSignal = cancellationSignal,
            ) ?: return null
        return resolver.openAssetFileDescriptor(uri, "wt", cancellationSignal)?.createOutputStream()
    }

    override fun delete(): Boolean = DocumentsContract.deleteDocument(resolver, directoryUri)
}

private data class DocumentEntry(
    val name: String,
    val mimeType: String,
    val uri: Uri,
    val lastModifiedMillis: Long?,
) {
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

/** 只保留协议关心的条目；扫描预算允许全部合法引用，再额外容忍有限无关文件。 */
private fun queryRelevantChildren(
    resolver: ContentResolver,
    treeUri: Uri,
    parentDocumentUri: Uri,
    acceptedNames: Set<String>,
    maxUnrelatedEntries: Int,
    cancellationSignal: CancellationSignal,
): List<DocumentEntry> {
    val childrenUri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parentDocumentUri),
        )
    val projection =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    val maxVisitedEntries = acceptedNames.size.toLong() + maxUnrelatedEntries
    return resolver
        .query(childrenUri, projection, null, null, null, cancellationSignal)
        ?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            var visitedEntries = 0L
            buildList {
                while (cursor.moveToNext()) {
                    cancellationSignal.throwIfCanceled()
                    visitedEntries += 1L
                    if (visitedEntries > maxVisitedEntries) {
                        throw FileNotFoundException("Backup directory contains too many unrelated entries")
                    }
                    val name = cursor.getString(nameIndex)
                    if (name !in acceptedNames) continue
                    val documentId = cursor.getString(idIndex)
                    add(
                        DocumentEntry(
                            name = name,
                            mimeType = cursor.getString(mimeIndex),
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            lastModifiedMillis =
                                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                                    cursor.getLong(modifiedIndex)
                                } else {
                                    null
                                },
                        ),
                    )
                }
            }
        } ?: throw FileNotFoundException("Cannot list backup directory")
}

private fun queryDocumentName(
    resolver: ContentResolver,
    uri: Uri,
    cancellationSignal: CancellationSignal,
): String? =
    resolver
        .query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
            cancellationSignal,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        }

/** Provider 可以改写 displayName；固定布局必须复验实际名称，不能在 JSON 再造文件名映射。 */
private fun createExactDocument(
    resolver: ContentResolver,
    parentUri: Uri,
    mimeType: String,
    expectedName: String,
    cancellationSignal: CancellationSignal,
): Uri? {
    cancellationSignal.throwIfCanceled()
    var createdUri = DocumentsContract.createDocument(resolver, parentUri, mimeType, expectedName) ?: return null
    try {
        cancellationSignal.throwIfCanceled()
        if (queryDocumentName(resolver, createdUri, cancellationSignal) == expectedName) return createdUri
        createdUri = DocumentsContract.renameDocument(resolver, createdUri, expectedName) ?: createdUri
        cancellationSignal.throwIfCanceled()
        if (queryDocumentName(resolver, createdUri, cancellationSignal) == expectedName) return createdUri
        deleteDocumentIgnoringFailure(resolver, createdUri)
        return null
    } catch (error: Exception) {
        deleteDocumentIgnoringFailure(resolver, createdUri)
        throw error
    }
}

private fun deleteDocumentIgnoringFailure(
    resolver: ContentResolver,
    uri: Uri,
) {
    try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: Exception) {
        // 创建方会删除整个不完整备份目录；这里不能用次要清理错误覆盖根因。
    }
}

private fun Uri.asTreeDocumentUri(): Uri {
    if (!DocumentsContract.isTreeUri(this)) throw FileNotFoundException("A document tree URI is required")
    return DocumentsContract.buildDocumentUriUsingTree(this, DocumentsContract.getTreeDocumentId(this))
}

internal const val BACKUP_MANIFEST_FILE_NAME = "cardrecord_backup.json"
internal const val BACKUP_IMAGES_DIRECTORY_NAME = "card_images"
internal const val BACKUP_IMAGE_FILE_SUFFIX = ".image"
private const val JSON_MIME_TYPE = "application/json"
private const val BINARY_MIME_TYPE = "application/octet-stream"
private const val DEFAULT_MAX_UNRELATED_DIRECTORY_ENTRIES = 10_000
