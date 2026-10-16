package com.shuaji.cards.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.ContentResolverUserCardImageStore
import com.shuaji.cards.data.DateToken
import com.shuaji.cards.data.FailClosedTestUserCardImageStore
import com.shuaji.cards.data.ImageAssetId
import com.shuaji.cards.data.UserCardImageStore
import com.shuaji.cards.data.backup.TestData.card
import com.shuaji.cards.data.backup.TestData.folder
import com.shuaji.cards.data.backup.TestData.transaction
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.data.local.TransactionEntity
import com.shuaji.cards.data.reminder.AnnualFeeReminderStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackupRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: BackupRepository
    private lateinit var reminderStore: AnnualFeeReminderStore
    private lateinit var cardRepository: CardRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        cardRepository =
            CardRepository(
                database = db,
                cardDao = db.cardDao(),
                transactionDao = db.transactionDao(),
                folderDao = db.cardFolderDao(),
                clock = Clock.fixed(Instant.parse("2027-07-15T00:00:00Z"), ZoneOffset.UTC),
                zoneIdProvider = { ZoneOffset.UTC },
                boundaryTicks = flowOf(Unit),
                userImages = FailClosedTestUserCardImageStore,
            )
        reminderStore = AnnualFeeReminderStore(context)
        repo =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
                normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                userImages = FailClosedTestUserCardImageStore,
                reminderStore = reminderStore,
                directoryAccess = LocalBackupDirectoryAccess,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tempManifestFile(name: String = "backup.json"): File {
        val directory = tempFolder.newFolder(name.removeSuffix(".json"))
        return directory.resolve(BACKUP_MANIFEST_FILE_NAME).apply { check(createNewFile()) }
    }

    private fun File.backupDirectory(): File = if (isDirectory) this else checkNotNull(parentFile)

    private suspend fun importInspected(
        backup: File,
        mode: ImportMode,
        repository: BackupRepository = repo,
    ): ImportResult {
        val uri = Uri.fromFile(backup.backupDirectory())
        val inspected = repository.inspect(uri)
        return repository.import(uri, mode, inspected.manifestSha256)
    }

    private fun json(): Json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    private fun directoryManifest(directory: File): Pair<BackupBundle, JsonObject> {
        val text = directory.resolve(BACKUP_MANIFEST_FILE_NAME).readText(Charsets.UTF_8)
        return json().decodeFromString<BackupBundle>(text) to json().parseToJsonElement(text).jsonObject
    }

    private fun writeBackupDirectory(
        directory: File,
        bundle: BackupBundle,
        images: Map<String, ByteArray> = emptyMap(),
    ) {
        check(directory.isDirectory || directory.mkdirs())
        if (images.isNotEmpty()) {
            val imageDirectory = directory.resolve(BACKUP_IMAGES_DIRECTORY_NAME)
            check(imageDirectory.isDirectory || imageDirectory.mkdir())
            images.forEach { (name, bytes) ->
                imageDirectory.resolve(name).writeBytes(bytes)
            }
        }
        directory.resolve(BACKUP_MANIFEST_FILE_NAME).writeText(json().encodeToString(bundle), Charsets.UTF_8)
    }

    private suspend fun exportBackup(
        parentName: String,
        repository: BackupRepository = repo,
    ): Pair<File, ExportSummary> {
        val parent = tempFolder.newFolder(parentName)
        val summary = repository.export(Uri.fromFile(parent))
        val directory = parent.resolve(summary.directoryName)
        check(directory.isDirectory)
        return directory to summary
    }

    private fun repositoryWithImages(userImages: UserCardImageStore): BackupRepository =
        BackupRepository(
            context = context,
            database = db,
            cardDao = db.cardDao(),
            folderDao = db.cardFolderDao(),
            transactionDao = db.transactionDao(),
            normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
            userImages = userImages,
            reminderStore = reminderStore,
            directoryAccess = LocalBackupDirectoryAccess,
        )

    private fun seed(
        folders: List<CardFolderEntity> = emptyList(),
        cards: List<CardEntity> = emptyList(),
        transactions: List<TransactionEntity> = emptyList(),
    ) = runBlocking {
        folders.forEach { db.cardFolderDao().insert(it) }
        cards.forEach { db.cardDao().upsert(it) }
        transactions.forEach { db.transactionDao().insert(it) }
    }

    private suspend fun snapshotDatabase() =
        DatabaseSnapshot(
            folders = db.cardFolderDao().listAll().sortedBy(CardFolderEntity::id),
            cards = db.cardDao().listAll().sortedBy(CardEntity::id),
            transactions = db.transactionDao().listAll().sortedBy(TransactionEntity::id),
        )

    private data class DatabaseSnapshot(
        val folders: List<CardFolderEntity>,
        val cards: List<CardEntity>,
        val transactions: List<TransactionEntity>,
    )

    private fun assertLogicalSnapshotEquals(
        expected: DatabaseSnapshot,
        actual: DatabaseSnapshot,
    ) {
        assertEquals(
            expected.folders.map { it.copy(id = 0L) }.sortedBy { it.name },
            actual.folders.map { it.copy(id = 0L) }.sortedBy { it.name },
        )
        val expectedFolderName = expected.folders.associate { it.id to it.name }
        val actualFolderName = actual.folders.associate { it.id to it.name }
        assertEquals(
            expected.cards
                .map { it.copy(id = 0L, folderId = null) to it.folderId?.let(expectedFolderName::get) }
                .sortedBy { it.first.name },
            actual.cards
                .map { it.copy(id = 0L, folderId = null) to it.folderId?.let(actualFolderName::get) }
                .sortedBy { it.first.name },
        )
        val expectedCardName = expected.cards.associate { it.id to it.name }
        val actualCardName = actual.cards.associate { it.id to it.name }
        assertEquals(
            expected.transactions
                .map { expectedCardName.getValue(it.cardId) to it.occurredAtMillis }
                .sortedWith(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second }),
            actual.transactions
                .map { actualCardName.getValue(it.cardId) to it.occurredAtMillis }
                .sortedWith(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second }),
        )
    }

    @Test
    fun import_replace_clearsStaleNotifiedKeys_beforeApplyingSettings() =
        runTest {
            reminderStore.setEnabled(false)
            reminderStore.markNotified(cardId = 99L, thresholdDays = 10, dueDateToken = 1L)
            reminderStore.writeScheduledKeys(
                setOf(AnnualFeeReminderStore.scheduleKey(99L, 10, 1L)),
            )
            val directory = tempFolder.newFolder("replace-clears-reminder-state")
            writeBackupDirectory(
                directory,
                TestData.backupBundle(annualFeeRemindersEnabled = true),
            )
            importInspected(directory, ImportMode.REPLACE)
            assertTrue(reminderStore.isEnabled())
            assertFalse(reminderStore.wasNotified(99L, 10, 1L))
            assertTrue(reminderStore.readScheduledKeys().isEmpty())
        }

    @Test
    fun import_merge_doesNotOverwriteLocalReminderPreference() =
        runTest {
            reminderStore.setEnabled(true)
            val directory = tempFolder.newFolder("merge-keeps-reminder-on")
            writeBackupDirectory(
                directory,
                TestData.backupBundle(annualFeeRemindersEnabled = false),
            )
            val result = importInspected(directory, ImportMode.MERGE)
            assertFalse(
                "MERGE 不应因备份开关触发导入后权限申请",
                result.annualFeeRemindersEnabled,
            )
            assertTrue(reminderStore.isEnabled())
        }

    @Test
    fun export_writeFailure_deletesIncompleteDirectory() =
        runTest {
            var deleted = false
            val failingDirectory =
                object : BackupDirectory {
                    override val displayName: String = "partial"
                    override val lastModifiedMillis: Long? = null

                    override fun openManifestInput(cancellationSignal: android.os.CancellationSignal): InputStream? = null

                    override fun openManifestOutput(cancellationSignal: android.os.CancellationSignal): OutputStream =
                        throw IOException("disk full")

                    override fun indexImageInputs(
                        assetFileNames: Set<String>,
                        cancellationSignal: android.os.CancellationSignal,
                    ) = Unit

                    override fun openImageInput(
                        assetFileName: String,
                        cancellationSignal: android.os.CancellationSignal,
                    ): InputStream? = null

                    override fun openImageOutput(
                        assetFileName: String,
                        cancellationSignal: android.os.CancellationSignal,
                    ): OutputStream? = null

                    override fun delete(): Boolean {
                        deleted = true
                        return true
                    }
                }
            val failingRepository =
                BackupRepository(
                    context = context,
                    database = db,
                    cardDao = db.cardDao(),
                    folderDao = db.cardFolderDao(),
                    transactionDao = db.transactionDao(),
                    normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                    userImages = FailClosedTestUserCardImageStore,
                    reminderStore = reminderStore,
                    directoryAccess =
                        object : BackupDirectoryAccess {
                            override fun createBackup(
                                parentUri: Uri,
                                suggestedName: String,
                                cancellationSignal: android.os.CancellationSignal,
                            ): BackupDirectory = failingDirectory

                            override fun openBackup(
                                directoryUri: Uri,
                                cancellationSignal: android.os.CancellationSignal,
                            ): BackupDirectory = error("not used")
                        },
                )

            assertThrows(BackupException::class.java) {
                runBlocking { failingRepository.export(Uri.EMPTY) }
            }
            assertTrue(deleted)
        }

    @Test
    fun ownedImage_exportAndReplaceImport_restoresBytesWithoutOriginalGalleryUri() =
        runTest {
            var galleryBytes: ByteArray? = ONE_PIXEL_PNG
            val sourceImages =
                ContentResolverUserCardImageStore(
                    contentResolver = context.contentResolver,
                    cardDao = db.cardDao(),
                    rootDirectory = tempFolder.newFolder("source-images"),
                    openSource = { _, _ -> ByteArrayInputStream(requireNotNull(galleryBytes)) },
                    validateSource = { file -> file.readBytes().contentEquals(ONE_PIXEL_PNG) },
                )
            val staged = sourceImages.stageFromUri("content://gallery/original")
            db.cardDao().upsert(
                card(name = "可恢复图片卡")
                    .copy(
                        imageSourceType = ImageSourceType.USER,
                        imageUri = null,
                        imageAssetId = staged.assetId.value,
                    ),
            )
            sourceImages.releaseLeases(setOf(staged))
            sourceImages.collectGarbage()
            galleryBytes = null
            val (backupDirectory, _) =
                exportBackup("owned-image-parent", repositoryWithImages(sourceImages))
            assertTrue(backupDirectory.resolve(BACKUP_IMAGES_DIRECTORY_NAME).isDirectory)
            assertArrayEquals(
                ONE_PIXEL_PNG,
                backupDirectory
                    .resolve(BACKUP_IMAGES_DIRECTORY_NAME)
                    .resolve("${staged.assetId.value}$BACKUP_IMAGE_FILE_SUFFIX")
                    .readBytes(),
            )

            val restoredImages =
                ContentResolverUserCardImageStore(
                    contentResolver = context.contentResolver,
                    cardDao = db.cardDao(),
                    rootDirectory = tempFolder.newFolder("restored-images"),
                    openSource = { _, _ -> null },
                    validateSource = { file -> file.readBytes().contentEquals(ONE_PIXEL_PNG) },
                )
            val restoredRepository = repositoryWithImages(restoredImages)
            importInspected(backupDirectory, ImportMode.REPLACE, restoredRepository)
            assertTrue(backupDirectory.deleteRecursively())

            val restoredCard = db.cardDao().listAll().single()
            val restoredId = requireNotNull(ImageAssetId.parse(restoredCard.imageAssetId))
            assertNull(restoredCard.imageUri)
            assertArrayEquals(ONE_PIXEL_PNG, restoredImages.openAsset(restoredId).use { it.readBytes() })
        }

    @Test
    fun directory_missingReferencedImage_isRejectedBeforeDatabaseWrite() =
        runTest {
            val existing = db.cardDao().upsert(card(name = "现有卡"))
            val missingId = "a".repeat(64)
            val directory = tempFolder.newFolder("missing-image-backup")
            writeBackupDirectory(
                directory,
                TestData.backupBundle(
                    version = BackupBundle.SCHEMA_VERSION,
                    cards = listOf(card(id = 10L, name = "缺图卡").copy(imageAssetId = missingId)),
                ),
            )

            assertThrows(BackupException::class.java) {
                runBlocking { repo.inspect(Uri.fromFile(directory)) }
            }
            assertEquals("现有卡", db.cardDao().getById(existing)?.name)
        }

    @Test
    fun directory_rejectsImageWhoseBytesDoNotMatchAssetId() =
        runTest {
            val declaredId = "b".repeat(64)
            val directory = tempFolder.newFolder("hash-mismatch-backup")
            writeBackupDirectory(
                directory,
                TestData.backupBundle(
                    version = BackupBundle.SCHEMA_VERSION,
                    cards = listOf(card(id = 10L, name = "篡改图片").copy(imageAssetId = declaredId)),
                ),
                images = mapOf("$declaredId$BACKUP_IMAGE_FILE_SUFFIX" to ONE_PIXEL_PNG),
            )
            val images =
                ContentResolverUserCardImageStore(
                    contentResolver = context.contentResolver,
                    cardDao = db.cardDao(),
                    rootDirectory = tempFolder.newFolder("hash-mismatch-images"),
                    openSource = { _, _ -> null },
                    validateSource = { true },
                )

            assertThrows(BackupException::class.java) {
                runBlocking { repositoryWithImages(images).inspect(Uri.fromFile(directory)) }
            }
            assertTrue(
                tempFolder.root
                    .resolve("hash-mismatch-images/assets")
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun schemaThreeDirectory_replaceRoundtripPreservesEveryEntityField() =
        runTest {
            val images =
                ContentResolverUserCardImageStore(
                    contentResolver = context.contentResolver,
                    cardDao = db.cardDao(),
                    rootDirectory = tempFolder.newFolder("all-fields-images"),
                    openSource = { _, _ -> ByteArrayInputStream(ONE_PIXEL_PNG) },
                    validateSource = { file -> file.readBytes().contentEquals(ONE_PIXEL_PNG) },
                )
            val stagedImage = images.stageFromUri("content://gallery/all-fields")
            val folderId =
                db.cardFolderDao().insert(
                    folder(
                        name = "完整分组",
                        colorArgb = 0xFF112233.toInt(),
                        sortOrder = 7,
                        createdAtMillis = 111L,
                    ),
                )
            val cardId =
                db.cardDao().upsert(
                    card(
                        name = "完整卡片",
                        bank = "完整银行",
                        cardNumberMasked = "**** 9876",
                        cardType = CardType.CREDIT,
                        statementDay = 12,
                        repaymentDay = 27,
                        validUntilMillis = 222L,
                        nextDueDateMillis = DateToken.fromLocalDate(LocalDate.of(2029, 6, 1)),
                        requiredCount = 9,
                        colorArgb = 0xFF445566.toInt(),
                        note = "完整备注",
                        imageUri = null,
                        imageSourceType = ImageSourceType.USER,
                        imageProviderKey = "provider",
                        cardOrientation = CardOrientation.PORTRAIT,
                        folderId = folderId,
                        createdAtMillis = 333L,
                    ).copy(imageAssetId = stagedImage.assetId.value),
                )
            images.releaseLeases(setOf(stagedImage))
            images.collectGarbage()
            val plainNetworkCard =
                card(
                    id = 0L,
                    name = "纯色银联",
                    imageSourceType = ImageSourceType.NONE,
                    imageProviderKey = CardNetworkProvider.UNIONPAY.key,
                )
            val userNetworkCard =
                card(
                    id = 0L,
                    name = "图片万事达",
                    imageSourceType = ImageSourceType.USER,
                    imageProviderKey = CardNetworkProvider.MASTERCARD.key,
                )
            db.cardDao().upsert(plainNetworkCard)
            db.cardDao().upsert(userNetworkCard)
            db.transactionDao().insert(transaction(cardId = cardId, occurredAtMillis = 444L))
            val before = snapshotDatabase()
            val imageRepository = repositoryWithImages(images)
            val (file, _) = exportBackup("all-fields-parent", imageRepository)

            seed(cards = listOf(card(name = "杂质")))
            importInspected(file, ImportMode.REPLACE, imageRepository)

            assertEquals(BackupBundle.SCHEMA_VERSION, directoryManifest(file).first.version)
            assertEquals(3, BackupBundle.SCHEMA_VERSION)
            assertEquals(9, db.openHelper.readableDatabase.version)
            assertLogicalSnapshotEquals(before, snapshotDatabase())

            importInspected(file, ImportMode.MERGE, imageRepository)

            val mergedCards = db.cardDao().listAll()
            assertEquals(
                2,
                mergedCards.count {
                    it.name == "完整卡片" &&
                        it.cardType == CardType.CREDIT &&
                        it.statementDay == 12 &&
                        it.repaymentDay == 27
                },
            )
            assertEquals(
                2,
                mergedCards.count {
                    it.name == plainNetworkCard.name &&
                        it.imageSourceType == ImageSourceType.NONE &&
                        it.imageProviderKey == CardNetworkProvider.UNIONPAY.key
                },
            )
            assertEquals(
                2,
                mergedCards.count {
                    it.name == userNetworkCard.name &&
                        it.imageSourceType == ImageSourceType.USER &&
                        it.imageProviderKey == CardNetworkProvider.MASTERCARD.key
                },
            )
            assertEquals(
                2,
                mergedCards
                    .filter { it.name == plainNetworkCard.name }
                    .map(CardEntity::id)
                    .distinct()
                    .size,
            )
            assertEquals(
                2,
                mergedCards
                    .filter { it.name == userNetworkCard.name }
                    .map(CardEntity::id)
                    .distinct()
                    .size,
            )
        }

    @Test
    fun importNormalizationFailure_rollsBackWholeImport() =
        runTest {
            seed(
                folders = listOf(folder(id = 1L, name = "原分组")),
                cards = listOf(card(id = 2L, name = "原卡", folderId = 1L)),
                transactions = listOf(transaction(id = 3L, cardId = 2L)),
            )
            val before = snapshotDatabase()
            val file = tempManifestFile()
            file.writeText(
                json().encodeToString(
                    TestData.backupBundle(
                        cards = listOf(card(id = 20L, name = "备份卡")),
                    ),
                ),
                Charsets.UTF_8,
            )
            val failingRepository =
                BackupRepository(
                    context = context,
                    database = db,
                    cardDao = db.cardDao(),
                    folderDao = db.cardFolderDao(),
                    transactionDao = db.transactionDao(),
                    normalizeInTransaction = { error("归一化失败") },
                    userImages = FailClosedTestUserCardImageStore,
                    reminderStore = reminderStore,
                    directoryAccess = LocalBackupDirectoryAccess,
                )

            assertThrows(BackupException::class.java) {
                runBlocking { importInspected(file, ImportMode.REPLACE, failingRepository) }
            }

            assertEquals(before, snapshotDatabase())
        }

    @Test
    fun merge_appends_cards_and_remaps_folderId() =
        runTest {
            val bundle =
                TestData.backupBundle(
                    folders = listOf(folder(id = 10L, name = "A")),
                    cards =
                        listOf(
                            card(
                                id = 20L,
                                name = "C1",
                                colorArgb = 0xFF123456.toInt(),
                                imageSourceType = ImageSourceType.PROVIDER,
                                imageProviderKey = "visa",
                                cardOrientation = CardOrientation.PORTRAIT,
                                folderId = 10L,
                            ),
                            card(id = 21L, name = "C2", folderId = 9999L),
                        ),
                    transactions = emptyList(),
                )
            val file = tempManifestFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = importInspected(file, ImportMode.MERGE)

            val cards = db.cardDao().listAll()
            assertEquals(2, cards.size)

            val folders = db.cardFolderDao().listAll()
            val folderAId = folders.first { it.name == "A" }.id

            val c1 = cards.first { it.name == "C1" }
            assertEquals(folderAId, c1.folderId)
            assertEquals(0xFF123456.toInt(), c1.colorArgb)
            assertEquals(ImageSourceType.PROVIDER, c1.imageSourceType)
            assertEquals("visa", c1.imageProviderKey)
            assertEquals(CardOrientation.PORTRAIT, c1.cardOrientation)

            val c2 = cards.first { it.name == "C2" }
            assertNull(c2.folderId)
            assertEquals("C2 的非法 folderId 应计入 cardsSkippedInvalidFolder", 1, result.cardsSkippedInvalidFolder)
        }

    @Test
    fun merge_remaps_transaction_cardId() =
        runTest {
            val bundle =
                TestData.backupBundle(
                    folders = listOf(folder(id = 10L, name = "A")),
                    cards = listOf(card(id = 20L, name = "C", folderId = 10L)),
                    transactions =
                        listOf(
                            transaction(id = 100L, cardId = 20L, occurredAtMillis = 1_000L),
                            transaction(id = 101L, cardId = 20L, occurredAtMillis = 2_000L),
                        ),
                )
            val file = tempManifestFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            importInspected(file, ImportMode.MERGE)

            val transactions = db.transactionDao().listAll()
            assertEquals(2, transactions.size)
            val newCardId =
                db
                    .cardDao()
                    .listAll()
                    .single()
                    .id
            assertTrue(newCardId != 20L)
            assertTrue(transactions.all { it.cardId == newCardId })
        }

    @Test
    fun import_rejectsEveryUnsupportedVersion_withoutChangingDatabase() =
        runTest {
            val originalId = db.cardDao().upsert(card(name = "原卡"))

            listOf(1, 2, BackupBundle.SCHEMA_VERSION + 1, 99).forEach { version ->
                val file = tempManifestFile("unsupported-$version.json")
                file.writeText(
                    """{"version": $version, "cards": [], "folders": [], "transactions": []}""",
                    Charsets.UTF_8,
                )

                val exception =
                    assertThrows(BackupException::class.java) {
                        runBlocking { importInspected(file, ImportMode.REPLACE) }
                    }
                assertTrue(exception.message.orEmpty().contains("不兼容"))
                assertEquals("原卡", db.cardDao().getById(originalId)?.name)
            }
        }

    @Test
    fun import_malformed_json_throws_BackupException() =
        runTest {
            val file = tempManifestFile()
            file.writeText("""this is not json {""", Charsets.UTF_8)

            val ex =
                assertThrows(BackupException::class.java) {
                    runBlocking { importInspected(file, ImportMode.REPLACE) }
                }
            assertTrue(ex.message.orEmpty().contains("格式不正确"))
        }

    private companion object {
        val ONE_PIXEL_PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
    }
}
