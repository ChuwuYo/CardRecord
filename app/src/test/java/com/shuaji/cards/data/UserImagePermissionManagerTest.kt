package com.shuaji.cards.data

import android.content.ContentResolver
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import com.shuaji.cards.data.local.CardDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserImagePermissionManagerTest {
    @Test
    fun sharedPendingUri_isReleasedOnlyAfterLastOwnerFinishes() =
        runTest {
            val testUri = Uri.parse("content://cards/shared")
            val permission =
                mock<UriPermission> {
                    on { uri } doReturn testUri
                    on { isReadPermission } doReturn true
                }
            val resolver =
                mock<ContentResolver> {
                    on { persistedUriPermissions } doReturn listOf(permission)
                }
            val cardDao = mock<CardDao>()
            whenever(cardDao.listAll()).doReturn(emptyList())
            val store =
                ContentResolverUserImagePermissionStore(
                    contentResolver = resolver,
                    cardDao = cardDao,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            assertTrue(store.acquire(testUri.toString()))
            assertTrue(store.acquire(testUri.toString()))
            verify(resolver, times(1)).takePersistableUriPermission(testUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            store.releasePending(setOf(testUri.toString()))
            verify(resolver, never()).releasePersistableUriPermission(testUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            store.releasePending(setOf(testUri.toString()))
            verify(resolver, times(1)).releasePersistableUriPermission(testUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
