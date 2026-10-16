package com.shuaji.cards.data

import android.content.ContentResolver
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardEntity
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
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
