package de.traewelling.app.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PreferencesManagerStorageTest {
    @Test fun managersSharingAStoreShareItsHealthAndConfirmedSession() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = emptyList(), startObserving = false)
        val first = PreferencesManager(storage)
        val second = PreferencesManager(storage)
        assertSame(first.storageState, second.storageState)
        val original = first.getAuthSession()
        store.readFailures = 1
        assertTrue(runCatching { second.getAuthSession() }.exceptionOrNull() is PreferenceStorageException)
        assertEquals(original, first.authSession.first())
        assertFalse(first.storageState.value.isReady)
        assertTrue(first.retryStorage().isSuccess)
        assertEquals(original, second.getAuthSession())
    }

    @Test fun freshGettersNeverAuthorizeUsingThePresentationReplay() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        val manager = PreferencesManager(storage)
        val original = manager.getAuthSession()
        store.saved = store.saved.toMutablePreferences().also {
            AuthSessionPreferences.saveValidated(it, original.serverUrl, "new-token", "bob")
            AuthSessionPreferences.saveActiveStatusId(it, 99)
            it[PreferencesManager.KEY_GPS_TRACKING_ENABLED] = false
        }
        assertEquals(original, manager.authSession.first())
        assertEquals("new-token", manager.getAuthSession().accessToken)
        assertEquals(99, manager.getActiveStatusId())
        val config = manager.getTrackingConfiguration()
        assertEquals("new-token", config.session.accessToken)
        assertEquals(99, config.activeStatusId)
        assertFalse(config.gpsEnabled)
    }

    @Test fun failedSettingsWriteDoesNotChangeTheDisplayedOrSavedSetting() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, startObserving = false)
        val manager = PreferencesManager(storage)
        manager.getAuthSession()
        val original = manager.getGpsTrackingEnabled()
        store.writeFailure = IOException("readable but unwritable")
        assertTrue(runCatching { manager.setGpsTrackingEnabled(!original) }.exceptionOrNull() is PreferenceStorageException)
        assertEquals(original, manager.gpsTrackingEnabled.first())
        assertNull(store.saved[booleanPreferencesKey("gps_tracking_enabled")])
        assertFalse(manager.storageState.value.isReady)
    }

    @Test fun aHealthFailureBlocksWritesEvenWhenTheBackingStoreReturnsAnOldSnapshot() = runTest {
        val store = StorageFixture()
        val storage = PreferenceStorage(store, backgroundScope, retryDelaysMillis = emptyList(), startObserving = false)
        val manager = PreferencesManager(storage)
        val session = manager.getAuthSession()
        store.readFailures = 1
        runCatching { manager.getUsername() }
        assertTrue(runCatching { manager.clearSessionIfMatches(session) }.exceptionOrNull() is PreferenceStorageException)
        assertEquals(0, store.writeAttempts)
        assertEquals(session, AuthSessionPreferences.read(store.saved))
    }
}
