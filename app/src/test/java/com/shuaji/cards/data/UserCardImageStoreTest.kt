package com.shuaji.cards.data

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.ImageSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserCardImageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stage_copiesImageIntoOwnedStorage_andNoLongerDependsOnSource() =
        runTest {
            var source: ByteArray? = ONE_PIXEL_PNG
            val store = store(openSource = { _, _ -> ByteArrayInputStream(requireNotNull(source)) })

            val staged = store.stageFromUri("content://gallery/card")
            source = null

            assertTrue(store.resolve(staged.assetId).startsWith("file:"))
            assertArrayEquals(ONE_PIXEL_PNG, store.openAsset(staged.assetId).use { it.readBytes() })
        }

    @Test
    fun releasedLease_keepsAssetReferencedByDatabase() =
        runTest {
            val cardDao = mock<CardDao>()
            whenever(cardDao.listAll()).doReturn(emptyList())
            val store = store(cardDao = cardDao)
            val staged = store.stageFromUri("content://gallery/shared")
            whenever(cardDao.listAll()).doReturn(listOf(card(staged.assetId.value)))

            store.releaseLeases(setOf(staged))
            store.collectGarbage()

            assertArrayEquals(ONE_PIXEL_PNG, store.openAsset(staged.assetId).use { it.readBytes() })
        }

    @Test
    fun garbageCollection_snapshotsLeasesBeforeDatabaseToProtectConcurrentSave() {
        val databaseReadStarted = CountDownLatch(1)
        val allowDatabaseReadToReturn = CountDownLatch(1)
        val currentCards = AtomicReference<List<CardEntity>>(emptyList())
        val cardDao =
            mock<CardDao> {
                onBlocking { listAll() } doAnswer {
                    val captured = currentCards.get()
                    databaseReadStarted.countDown()
                    allowDatabaseReadToReturn.await()
                    captured
                }
            }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store =
            ContentResolverUserCardImageStore(
                contentResolver = mock<ContentResolver>(),
                cardDao = cardDao,
                rootDirectory = temporaryFolder.root.resolve("gc-save-race"),
                dispatcher = dispatcher,
                openSource = { _, _ -> ByteArrayInputStream(ONE_PIXEL_PNG) },
                validateSource = { file -> file.readBytes().contentEquals(ONE_PIXEL_PNG) },
            )
        try {
            val staged = runBlocking { store.stageFromUri("content://gallery/save-race") }
            val collection = scope.async { store.collectGarbage() }
            assertTrue(databaseReadStarted.await(5, TimeUnit.SECONDS))

            // 模拟保存先提交数据库、再结束租约；DAO 已经捕获旧快照，只有更早的租约快照能保护文件。
            currentCards.set(listOf(card(staged.assetId.value)))
            store.releaseLeases(setOf(staged))
            allowDatabaseReadToReturn.countDown()
            runBlocking { collection.await() }

            assertArrayEquals(ONE_PIXEL_PNG, store.openAsset(staged.assetId).use { it.readBytes() })
        } finally {
            allowDatabaseReadToReturn.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun garbageCollection_deletesHashNamedFileWithoutAssetSuffix() =
        runTest {
            val cardDao = mock<CardDao>()
            whenever(cardDao.listAll()).doReturn(emptyList())
            val store = store(cardDao = cardDao)
            val staged = store.stageFromUri("content://gallery/invalid-name")
            whenever(cardDao.listAll()).doReturn(listOf(card(staged.assetId.value)))
            val invalidFile =
                temporaryFolder.root
                    .resolve("card-images/assets/${staged.assetId.value}")
                    .apply { writeBytes(ONE_PIXEL_PNG) }

            store.releaseLeases(setOf(staged))
            store.collectGarbage()

            assertFalse(invalidFile.exists())
            assertArrayEquals(ONE_PIXEL_PNG, store.openAsset(staged.assetId).use { it.readBytes() })
        }

    @Test
    fun releasedLease_deletesUnreferencedOwnedAssetDuringReconcile() =
        runTest {
            val store = store()
            val staged = store.stageFromUri("content://gallery/cancelled")

            store.releaseLeases(setOf(staged))
            store.collectGarbage()

            assertTrue(
                temporaryFolder.root
                    .resolve("card-images/assets")
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
            assertThrows(UserImageMissingException::class.java) {
                store.openAsset(staged.assetId)
            }
        }

    @Test
    fun stage_replacesCorruptFileAtExistingContentAddress() =
        runTest {
            val store = store()
            val first = store.stageFromUri("content://gallery/first")
            temporaryFolder.root
                .resolve("card-images/assets/${first.assetId.value}.image")
                .writeText("corrupt")

            val repaired = store.stageFromUri("content://gallery/repair")

            assertTrue(first.assetId == repaired.assetId)
            assertArrayEquals(ONE_PIXEL_PNG, store.openAsset(repaired.assetId).use { it.readBytes() })
        }

    @Test
    fun stage_rejectsBytesThatAreNotAnImage_withoutLeavingOwnedFile() =
        runTest {
            val store = store(openSource = { _, _ -> ByteArrayInputStream("not-image".encodeToByteArray()) })

            assertThrows(UserImageImportException::class.java) {
                kotlinx.coroutines.runBlocking { store.stageFromUri("content://gallery/not-image") }
            }
            assertTrue(
                temporaryFolder.root
                    .walkTopDown()
                    .filter { it.isFile }
                    .none(),
            )
        }

    @Test
    fun selectionSizeLimit_acceptsExactSize_andRejectsOneExtraWithoutResidue() =
        runTest {
            val root = temporaryFolder.root.resolve("selection-size-limit")
            var sourceBytes = ByteArray(8) { it.toByte() }
            val store =
                ContentResolverUserCardImageStore(
                    contentResolver = mock<ContentResolver>(),
                    cardDao =
                        mock<CardDao> {
                            onBlocking { listAll() } doReturn emptyList()
                        },
                    rootDirectory = root,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    openSource = { _, _ -> ByteArrayInputStream(sourceBytes) },
                    validateSource = { true },
                    maxSelectionBytes = 8L,
                )

            val accepted = store.stageFromUri("content://gallery/exact-limit")
            assertArrayEquals(sourceBytes, store.openAsset(accepted.assetId).use { it.readBytes() })
            store.releaseLeases(setOf(accepted))
            store.collectGarbage()

            sourceBytes = ByteArray(9) { it.toByte() }
            assertThrows(UserImageImportException::class.java) {
                runBlocking { store.stageFromUri("content://gallery/over-limit") }
            }
            assertTrue(root.walkTopDown().filter(File::isFile).none())
        }

    @Test
    fun cancellationDuringBlockingRead_closesSourceAndDeletesUnreferencedAsset() {
        val readStarted = CountDownLatch(1)
        val sourceClosed = CountDownLatch(1)
        val input =
            object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    readStarted.countDown()
                    sourceClosed.await()
                    throw IOException("closed")
                }

                override fun close() {
                    sourceClosed.countDown()
                }
            }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store =
            ContentResolverUserCardImageStore(
                contentResolver = mock<ContentResolver>(),
                cardDao =
                    mock<CardDao> {
                        onBlocking { listAll() } doReturn emptyList()
                    },
                rootDirectory = temporaryFolder.root.resolve("cancelled-copy"),
                dispatcher = dispatcher,
                openSource = { _, _ -> input },
                validateSource = { file -> file.readBytes().contentEquals(ONE_PIXEL_PNG) },
            )
        try {
            val copy = scope.async { store.stageFromUri("content://gallery/cancel-race") }
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))

            copy.cancel(CancellationException("test cancellation"))

            assertThrows(CancellationException::class.java) {
                runBlocking { copy.await() }
            }
            assertTrue("取消必须主动关闭来源流", sourceClosed.await(5, TimeUnit.SECONDS))
            assertTrue(
                temporaryFolder.root
                    .resolve("cancelled-copy/assets")
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        } finally {
            sourceClosed.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun cancellationAfterCopyBeforePromotion_deletesPreparedFile() {
        val databaseReadStarted = CountDownLatch(1)
        val allowDatabaseReadToReturn = CountDownLatch(1)
        val validationFinished = CountDownLatch(1)
        val firstDatabaseRead = AtomicBoolean(true)
        val cardDao =
            mock<CardDao> {
                onBlocking { listAll() } doAnswer {
                    if (firstDatabaseRead.compareAndSet(true, false)) {
                        databaseReadStarted.countDown()
                        allowDatabaseReadToReturn.await()
                    }
                    emptyList()
                }
            }
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val root = temporaryFolder.root.resolve("cancelled-after-copy")
        val store =
            ContentResolverUserCardImageStore(
                contentResolver = mock<ContentResolver>(),
                cardDao = cardDao,
                rootDirectory = root,
                dispatcher = dispatcher,
                openSource = { _, _ -> ByteArrayInputStream(ONE_PIXEL_PNG) },
                validateSource = { file ->
                    file.readBytes().contentEquals(ONE_PIXEL_PNG).also { validationFinished.countDown() }
                },
            )
        try {
            val maintenance = scope.async { store.collectGarbage() }
            assertTrue(databaseReadStarted.await(5, TimeUnit.SECONDS))
            val expectedId = ImageAssetId.fromDigest(MessageDigest.getInstance("SHA-256").digest(ONE_PIXEL_PNG))
            val stage =
                scope.async {
                    store.stageFromBackup(
                        expectedAssetId = expectedId,
                        input = ByteArrayInputStream(ONE_PIXEL_PNG),
                        maxBytes = ONE_PIXEL_PNG.size.toLong(),
                    )
                }
            assertTrue(validationFinished.await(5, TimeUnit.SECONDS))

            stage.cancel(CancellationException("cancel after copy"))
            allowDatabaseReadToReturn.countDown()
            runBlocking { maintenance.await() }
            assertThrows(CancellationException::class.java) { runBlocking { stage.await() } }

            assertTrue(root.walkTopDown().filter(File::isFile).none())
        } finally {
            allowDatabaseReadToReturn.countDown()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun legacyMigration_adoptsUriOnlyAfterOwnedCopySucceeds() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val cardId =
                    database.cardDao().upsert(
                        card().copy(
                            imageUri = "content://gallery/legacy",
                            imageSourceType = ImageSourceType.USER.key,
                        ),
                    )
                var source: ByteArray? = ONE_PIXEL_PNG
                val store =
                    ContentResolverUserCardImageStore(
                        contentResolver = context.contentResolver,
                        cardDao = database.cardDao(),
                        rootDirectory = temporaryFolder.root.resolve("legacy-images"),
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                        openSource = { _, _ -> ByteArrayInputStream(requireNotNull(source)) },
                        validateSource = { file -> file.readBytes().contentEquals(ONE_PIXEL_PNG) },
                    )

                store.migrateLegacyImages()
                store.collectGarbage()
                source = null

                val migrated = requireNotNull(database.cardDao().getById(cardId))
                val assetId = requireNotNull(ImageAssetId.parse(migrated.imageAssetId))
                assertTrue(migrated.imageUri == null)
                assertArrayEquals(ONE_PIXEL_PNG, store.openAsset(assetId).use { it.readBytes() })
            } finally {
                database.close()
            }
        }

    private fun store(
        cardDao: CardDao =
            mock<CardDao> {
                onBlocking { listAll() } doReturn emptyList()
            },
        openSource: (String, android.os.CancellationSignal) -> ByteArrayInputStream = { _, _ ->
            ByteArrayInputStream(ONE_PIXEL_PNG)
        },
    ): ContentResolverUserCardImageStore =
        ContentResolverUserCardImageStore(
            contentResolver = mock<ContentResolver>(),
            cardDao = cardDao,
            rootDirectory = temporaryFolder.root.resolve("card-images"),
            dispatcher = UnconfinedTestDispatcher(),
            openSource = openSource,
            validateSource = { file -> file.readBytes().contentEquals(ONE_PIXEL_PNG) },
        )

    private fun card(imageAssetId: String? = null): CardEntity =
        CardEntity(
            name = "图片卡",
            bank = "",
            cardNumberMasked = "",
            requiredCount = 6,
            colorArgb = 0,
            imageAssetId = imageAssetId,
        )

    private companion object {
        val ONE_PIXEL_PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
    }
}
