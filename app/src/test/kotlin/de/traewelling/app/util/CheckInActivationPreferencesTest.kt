package de.traewelling.app.util

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class CheckInActivationPreferencesTest {
    private val keys = AuthSessionPreferences

    @Test fun idleSessionCanStartAndTheSameJourneyRetainsItsProgress() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://example.test", "test-token", "alice")
        val session = keys.read(prefs)

        assertTrue(keys.saveActiveStatusIdIfIdleOrSame(prefs, session, 42))
        assertEquals("42", prefs[keys.activeStatusId])
        keys.saveTrackingState(prefs, 42, "current-progress", session)
        assertTrue(keys.saveActiveStatusIdIfIdleOrSame(prefs, session, 42))
        assertEquals("current-progress", prefs[keys.trackingState])
        assertEquals(session, keys.read(prefs))
    }

    @Test fun recordingAnotherCheckInDoesNotReplaceAnActiveJourneyOrItsProgress() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://example.test", "test-token", "alice")
        val session = keys.read(prefs)
        keys.saveActiveStatusIdIfMatches(prefs, session, 42)
        keys.saveTrackingState(prefs, 42, "current-progress", session)

        assertFalse(keys.saveActiveStatusIdIfIdleOrSame(prefs, session, 99))
        assertEquals("42", prefs[keys.activeStatusId])
        assertEquals("current-progress", prefs[keys.trackingState])
    }

    @Test fun oldOrUnauthenticatedSessionsAndInvalidIdsCannotActivateAnything() {
        val prefs = mutablePreferencesOf()
        assertFalse(keys.saveActiveStatusIdIfIdleOrSame(prefs, keys.read(prefs), 42))
        keys.saveValidated(prefs, "https://example.test", "test-token", "alice")
        val old = keys.read(prefs)
        keys.saveValidated(prefs, old.serverUrl, old.accessToken!!, "alice")
        assertFalse(keys.saveActiveStatusIdIfIdleOrSame(prefs, old, 42))
        for (id in listOf(0, -1)) {
            assertFalse(keys.saveActiveStatusIdIfIdleOrSame(prefs, keys.read(prefs), id))
        }
        assertNull(prefs[keys.activeStatusId])
    }

    @Test fun anUnparseableExistingJourneyIsPreservedInsteadOfSilentlyOverwritten() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://example.test", "test-token", "alice")
        prefs[keys.activeStatusId] = "unreadable"
        prefs[keys.trackingState] = "preserved-progress"

        assertFalse(keys.saveActiveStatusIdIfIdleOrSame(prefs, keys.read(prefs), 42))
        assertEquals("unreadable", prefs[keys.activeStatusId])
        assertEquals("preserved-progress", prefs[keys.trackingState])
    }
}
