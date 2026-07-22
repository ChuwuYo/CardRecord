package com.shuaji.cards.data.backup

import android.Manifest
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBackupDirectoryAccessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var authority: String
    private lateinit var provider: TestDocumentsProvider
    private lateinit var rootTreeUri: Uri

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        authority = "com.shuaji.cards.test.documents.${AUTHORITY_SEQUENCE.incrementAndGet()}"
        provider = TestDocumentsProvider(temporaryFolder.newFolder("documents"))
        provider.attachInfo(
            context,
            ProviderInfo().apply {
                authority = this@AndroidBackupDirectoryAccessTest.authority
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            },
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)
        rootTreeUri = DocumentsContract.buildTreeDocumentUri(authority, ROOT_ID)
    }

    @Test
    fun openBackup_requiresExactlyOneManifest() {
        val backupId = provider.addDirectory(ROOT_ID, "backup")
        val access = access(maxUnrelatedEntries = 4)
        val backupTree = DocumentsContract.buildTreeDocumentUri(authority, backupId)

        assertThrows(FileNotFoundException::class.java) {
            access.openBackup(backupTree, CancellationSignal())
        }

        provider.addFile(backupId, BACKUP_MANIFEST_FILE_NAME, byteArrayOf(1))
        provider.addFile(backupId, BACKUP_MANIFEST_FILE_NAME, byteArrayOf(2))

        assertThrows(FileNotFoundException::class.java) {
            access.openBackup(backupTree, CancellationSignal())
        }
    }

    @Test
    fun imageIndex_usesOnlyReferencedExactNames_andRejectsAmbiguity() {
        val backupId = provider.addDirectory(ROOT_ID, "backup")
        provider.addFile(backupId, BACKUP_MANIFEST_FILE_NAME, byteArrayOf(1))
        val imagesId = provider.addDirectory(backupId, BACKUP_IMAGES_DIRECTORY_NAME)
        val assetName = "${"a".repeat(64)}$BACKUP_IMAGE_FILE_SUFFIX"
        provider.addFile(imagesId, assetName, byteArrayOf(3, 4, 5))
        val directory =
            access(maxUnrelatedEntries = 4).openBackup(
                DocumentsContract.buildTreeDocumentUri(authority, backupId),
                CancellationSignal(),
            )

        directory.indexImageInputs(emptySet(), CancellationSignal())
        assertEquals(0, provider.childQueryCount(imagesId))

        directory.indexImageInputs(setOf(assetName), CancellationSignal())
        val restored =
            requireNotNull(directory.openImageInput(assetName, CancellationSignal())).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(3, 4, 5), restored)
        assertEquals(1, provider.childQueryCount(imagesId))

        provider.addFile(imagesId, assetName, byteArrayOf(6))
        assertThrows(FileNotFoundException::class.java) {
            directory.indexImageInputs(setOf(assetName), CancellationSignal())
        }
    }

    @Test
    fun imageIndex_allowsReferencedEntriesPlusBudget_butRejectsOneMoreUnrelatedEntry() {
        val backupId = provider.addDirectory(ROOT_ID, "backup")
        provider.addFile(backupId, BACKUP_MANIFEST_FILE_NAME, byteArrayOf(1))
        val imagesId = provider.addDirectory(backupId, BACKUP_IMAGES_DIRECTORY_NAME)
        provider.addFile(imagesId, "unrelated-1", byteArrayOf(1))
        provider.addFile(imagesId, "unrelated-2", byteArrayOf(1))
        val assetName = "${"b".repeat(64)}$BACKUP_IMAGE_FILE_SUFFIX"
        provider.addFile(imagesId, assetName, byteArrayOf(7))
        val directory =
            access(maxUnrelatedEntries = 2).openBackup(
                DocumentsContract.buildTreeDocumentUri(authority, backupId),
                CancellationSignal(),
            )

        directory.indexImageInputs(setOf(assetName), CancellationSignal())

        provider.addFile(imagesId, "unrelated-3", byteArrayOf(1))
        assertThrows(FileNotFoundException::class.java) {
            directory.indexImageInputs(setOf(assetName), CancellationSignal())
        }
    }

    @Test
    fun manifestCreation_repairsProviderChangedName() {
        provider.createdName = { mimeType, requested ->
            if (mimeType == "application/json") "$requested-provider" else requested
        }
        provider.renameToRequestedName = true
        val backup = access().createBackup(rootTreeUri, "backup", CancellationSignal())

        assertNotNull(backup.openManifestOutput(CancellationSignal())?.also { it.close() })

        val backupNode = provider.childrenOf(ROOT_ID).single { it.mimeType == DIRECTORY_MIME_TYPE }
        assertTrue(provider.childrenOf(backupNode.id).any { it.name == BACKUP_MANIFEST_FILE_NAME })
        assertTrue(provider.deletedIds.isEmpty())
    }

    @Test
    fun manifestCreation_deletesFileWhenProviderCannotRestoreExactName() {
        provider.createdName = { mimeType, requested ->
            if (mimeType == "application/json") "$requested-provider" else requested
        }
        provider.renameToRequestedName = false
        val backup = access().createBackup(rootTreeUri, "backup", CancellationSignal())

        val output = backup.openManifestOutput(CancellationSignal())

        assertEquals(null, output)
        val backupNode = provider.childrenOf(ROOT_ID).single { it.mimeType == DIRECTORY_MIME_TYPE }
        assertFalse(provider.childrenOf(backupNode.id).any { it.mimeType != DIRECTORY_MIME_TYPE })
        assertEquals(1, provider.deletedIds.size)
    }

    private fun access(maxUnrelatedEntries: Int = 10): AndroidBackupDirectoryAccess =
        AndroidBackupDirectoryAccess(
            context = ApplicationProvider.getApplicationContext(),
            maxUnrelatedDirectoryEntries = maxUnrelatedEntries,
        )

    private class TestDocumentsProvider(
        private val storageRoot: File,
    ) : DocumentsProvider() {
        private val nodes = linkedMapOf<String, Node>()
        private val childQueries = mutableMapOf<String, Int>()
        private var nextId = 0

        var createdName: (mimeType: String, requestedName: String) -> String = { _, requested -> requested }
        var renameToRequestedName: Boolean = true
        val deletedIds = mutableListOf<String>()

        init {
            nodes[ROOT_ID] = Node(ROOT_ID, null, "root", DIRECTORY_MIME_TYPE, storageRoot)
        }

        override fun onCreate(): Boolean = true

        override fun isChildDocument(
            parentDocumentId: String,
            documentId: String,
        ): Boolean {
            var current = nodes[documentId]
            while (current?.parentId != null) {
                if (current.parentId == parentDocumentId) return true
                current = nodes[current.parentId]
            }
            return false
        }

        fun addDirectory(
            parentId: String,
            name: String,
        ): String = addNode(parentId, name, DIRECTORY_MIME_TYPE)

        fun addFile(
            parentId: String,
            name: String,
            bytes: ByteArray,
        ): String = addNode(parentId, name, "application/octet-stream").also { nodes.getValue(it).file.writeBytes(bytes) }

        fun childrenOf(parentId: String): List<Node> = nodes.values.filter { it.parentId == parentId }

        fun childQueryCount(parentId: String): Int = childQueries[parentId] ?: 0

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS)

        /** Robolectric 直接走旧式 ContentResolver 查询；真实 DocumentsProvider 会在框架层完成同一路由。 */
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
            cancellationSignal: CancellationSignal?,
        ): Cursor =
            if (uri.lastPathSegment == "children") {
                queryChildDocuments(DocumentsContract.getDocumentId(uri), projection, sortOrder)
            } else {
                queryDocument(DocumentsContract.getDocumentId(uri), projection)
            }

        override fun queryDocument(
            documentId: String,
            projection: Array<out String>?,
        ): Cursor = documentCursor(projection, listOf(requireNode(documentId)))

        override fun queryChildDocuments(
            parentDocumentId: String,
            projection: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            childQueries[parentDocumentId] = childQueryCount(parentDocumentId) + 1
            return documentCursor(projection, childrenOf(parentDocumentId))
        }

        override fun openDocument(
            documentId: String,
            mode: String,
            signal: CancellationSignal?,
        ): ParcelFileDescriptor =
            ParcelFileDescriptor.open(
                requireNode(documentId).file,
                ParcelFileDescriptor.parseMode(mode),
            )

        override fun createDocument(
            parentDocumentId: String,
            mimeType: String,
            displayName: String,
        ): String = addNode(parentDocumentId, createdName(mimeType, displayName), mimeType)

        override fun renameDocument(
            documentId: String,
            displayName: String,
        ): String {
            if (renameToRequestedName) requireNode(documentId).name = displayName
            return documentId
        }

        override fun deleteDocument(documentId: String) {
            deleteRecursively(documentId)
        }

        private fun addNode(
            parentId: String,
            name: String,
            mimeType: String,
        ): String {
            requireNode(parentId)
            val id = "node-${++nextId}"
            val file = storageRoot.resolve(id)
            if (mimeType == DIRECTORY_MIME_TYPE) {
                check(file.mkdir())
            } else {
                check(file.createNewFile())
            }
            nodes[id] = Node(id, parentId, name, mimeType, file)
            return id
        }

        private fun deleteRecursively(documentId: String) {
            childrenOf(documentId).map(Node::id).forEach(::deleteRecursively)
            nodes.remove(documentId)?.file?.deleteRecursively()
            deletedIds += documentId
        }

        private fun requireNode(documentId: String): Node = nodes[documentId] ?: throw FileNotFoundException(documentId)

        private fun documentCursor(
            projection: Array<out String>?,
            values: List<Node>,
        ): Cursor {
            val columns = projection ?: DOCUMENT_COLUMNS
            return MatrixCursor(columns).apply {
                values.forEach { node ->
                    val row = newRow()
                    columns.forEach { column ->
                        row.add(
                            when (column) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                                DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mimeType
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> node.file.lastModified()
                                DocumentsContract.Document.COLUMN_SIZE -> node.file.length()
                                DocumentsContract.Document.COLUMN_FLAGS -> 0
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }

    private data class Node(
        val id: String,
        val parentId: String?,
        var name: String,
        val mimeType: String,
        val file: File,
    )

    private companion object {
        const val ROOT_ID = "root"
        const val DIRECTORY_MIME_TYPE = DocumentsContract.Document.MIME_TYPE_DIR
        val AUTHORITY_SEQUENCE = AtomicInteger()
        val DOCUMENT_COLUMNS =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        val ROOT_COLUMNS =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
            )
    }
}
