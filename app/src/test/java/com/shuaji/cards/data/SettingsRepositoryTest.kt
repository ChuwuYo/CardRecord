package com.shuaji.cards.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SettingsRepositoryTest {
    @Test
    fun setThemeMode_updatesDataStoreAndStartupCache() =
        runTest {
            val dataStore = InMemoryDataStore()
            val cache = RecordingThemeModeStartupCache()
            val repository = SettingsRepository(dataStore, cache)

            repository.setThemeMode(ThemeMode.DARK)

            assertEquals(ThemeMode.DARK, repository.themeSettings.first().themeMode)
            assertEquals(ThemeMode.DARK, cache.read())
        }

    @Test
    fun syncStartupThemeMode_onlyWritesWhenCacheDiffers() {
        val cache = RecordingThemeModeStartupCache()
        val repository = SettingsRepository(InMemoryDataStore(), cache)

        repository.syncStartupThemeMode(ThemeMode.LIGHT)
        repository.syncStartupThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, cache.read())
        assertEquals(1, cache.writeCount)
    }

    @Test
    fun themeSettings_ioReadFailureFallsBackToDefaults() =
        runTest {
            val repository = SettingsRepository(FailingDataStore(IOException("disk unavailable")))

            assertEquals(ThemeSettings(), repository.themeSettings.first())
        }

    @Test(expected = IllegalStateException::class)
    fun themeSettings_programmingFailureIsNotHidden() =
        runTest {
            SettingsRepository(FailingDataStore(IllegalStateException("broken invariant"))).themeSettings.first()
        }

    private class FailingDataStore(
        failure: Throwable,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw failure }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = error("本测试不执行写入")
    }

    private class InMemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class RecordingThemeModeStartupCache : ThemeModeStartupCache {
        private var mode: ThemeMode? = null
        var writeCount: Int = 0
            private set

        override fun read(): ThemeMode? = mode

        override fun write(mode: ThemeMode) {
            this.mode = mode
            writeCount += 1
        }
    }
}
