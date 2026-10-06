package de.traewelling.app.util

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class AuthSessionTest {
    private val keys = AuthSessionPreferences

    @Test fun emptyAndBlankTokensAreLoggedOut() {
        val prefs = mutablePreferencesOf()
        assertNull(keys.read(prefs).accessToken)
        prefs[keys.accessToken] = "  "
        assertNull(keys.read(prefs).accessToken)
    }

    @Test fun delayedUnauthorizedResponseCannotClearNewLogin() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        val old = keys.read(prefs)
        keys.saveValidated(prefs, "https://b.example", "token-b", "bob")
        assertFalse(keys.clearIfMatches(prefs, old))
        assertEquals("token-b", keys.read(prefs).accessToken)
        assertEquals("bob", prefs[keys.username])
    }

    @Test fun repeatedIdenticalCredentialsStillHaveDifferentGenerations() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        val old = keys.read(prefs)
        keys.clear(prefs)
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        assertNotEquals(old.revision, keys.read(prefs).revision)
        assertFalse(keys.clearIfMatches(prefs, old))
        assertFalse(keys.saveUsernameIfMatches(prefs, old, "stale"))
    }

    @Test fun validatedLoginClearsPreviousAccountAndTrackingData() {
        val prefs = mutablePreferencesOf(
            keys.refreshToken to "old-refresh", keys.clientId to "old-client",
            keys.clientSecret to "old-secret", keys.activeStatusId to "42",
            keys.trackingState to "old-trip", keys.recognitionEnabled to true
        )
        keys.saveValidated(prefs, "https://b.example/", "token-b", "bob")
        assertEquals("https://b.example", keys.read(prefs).serverUrl)
        assertEquals("bob", prefs[keys.username])
        listOf(keys.refreshToken, keys.clientId, keys.clientSecret, keys.activeStatusId, keys.trackingState)
            .forEach { assertNull(prefs[it]) }
        assertEquals(false, prefs[keys.recognitionEnabled])
    }

    @Test fun missingRefreshTokenRemovesPreviousRefreshToken() {
        val prefs = mutablePreferencesOf(keys.refreshToken to "old-refresh", keys.activeStatusId to "42")
        val old = keys.read(prefs)
        assertTrue(keys.saveTokensIfMatches(prefs, old, "rotated", null))
        assertNull(prefs[keys.refreshToken])
        assertEquals("42", prefs[keys.activeStatusId])
        assertNotEquals(old.revision, keys.read(prefs).revision)
    }

    @Test fun staleRefreshCannotOverwriteNewSession() {
        val prefs = mutablePreferencesOf()
        val old = keys.read(prefs)
        keys.saveValidated(prefs, "https://b.example", "token-b", "bob")
        assertFalse(keys.saveTokensIfMatches(prefs, old, "stale-token", "stale-refresh"))
        assertEquals("token-b", keys.read(prefs).accessToken)
        assertNull(prefs[keys.refreshToken])
    }

    @Test fun matchingUsernameValidationKeepsCredentialGeneration() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        val old = keys.read(prefs)
        assertTrue(keys.saveUsernameIfMatches(prefs, old, "renamed"))
        assertEquals(old, keys.read(prefs))
        assertEquals("renamed", prefs[keys.username])
    }

    @Test fun matchingLogoutClearsCredentialsAndActiveTrip() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        prefs[keys.activeStatusId] = "42"
        prefs[keys.trackingState] = "trip"
        val old = keys.read(prefs)
        assertTrue(keys.clearIfMatches(prefs, old))
        assertNull(keys.read(prefs).accessToken)
        assertNull(prefs[keys.activeStatusId])
        assertNull(prefs[keys.trackingState])
        assertFalse(keys.clearIfMatches(prefs, old))
    }

    @Test fun oldCheckInCannotActivateTripAfterAccountSwitch() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        val old = keys.read(prefs)
        keys.saveValidated(prefs, "https://b.example", "token-b", "bob")
        assertFalse(keys.saveActiveStatusIdIfMatches(prefs, old, 42))
        assertNull(prefs[keys.activeStatusId])
        assertFalse(keys.saveActiveStatusIdIfMatches(mutablePreferencesOf(), keys.read(mutablePreferencesOf()), 42))
    }

    @Test fun newTripClearsOldTrackingButSameTripRetainsIt() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        val session = keys.read(prefs)
        assertTrue(keys.saveActiveStatusIdIfMatches(prefs, session, 42))
        prefs[keys.trackingState] = "trip"
        assertTrue(keys.saveActiveStatusIdIfMatches(prefs, session, 42))
        assertEquals("trip", prefs[keys.trackingState])
        assertTrue(keys.saveActiveStatusIdIfMatches(prefs, session, 43))
        assertNull(prefs[keys.trackingState])
    }

    @Test fun diagnosticTextNeverContainsServerOrToken() {
        val session = AuthSession("https://private.example", "secret-value", "generation")
        assertFalse(session.toString().contains(session.serverUrl))
        assertFalse(session.toString().contains(session.accessToken!!))
    }

    @Test fun oldServiceCannotOverwriteOrClearSameNumericTripInNewAccount() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        val old = keys.read(prefs)
        keys.saveActiveStatusIdIfMatches(prefs, old, 42)
        keys.saveValidated(prefs, "https://b.example", "token-b", "bob")
        val current = keys.read(prefs)
        keys.saveActiveStatusIdIfMatches(prefs, current, 42)
        assertTrue(keys.saveTrackingState(prefs, 42, "new-trip", current))
        assertFalse(keys.saveTrackingState(prefs, 42, "old-trip", old))
        assertFalse(keys.clearActiveTracking(prefs, 42, old))
        assertEquals("new-trip", prefs[keys.trackingState])
        assertEquals("42", prefs[keys.activeStatusId])
        assertTrue(keys.clearActiveTracking(prefs, 42, current))
    }

    @Test fun oldServiceGenerationCannotWriteAfterIdenticalCredentialsRelogin() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        val old = keys.read(prefs)
        keys.clear(prefs)
        keys.saveValidated(prefs, old.serverUrl, old.accessToken!!, "alice")
        keys.saveActiveStatusIdIfMatches(prefs, keys.read(prefs), 42)
        assertFalse(keys.saveTrackingState(prefs, 42, "old-trip", old))
        assertFalse(keys.clearActiveTracking(prefs, 42, old))
    }

    @Test fun oldRecognitionStopCannotDisableNewAccountsOptIn() {
        val prefs = mutablePreferencesOf()
        keys.saveValidated(prefs, "https://a.example", "token-a", "alice")
        val old = keys.read(prefs)
        keys.saveValidated(prefs, "https://b.example", "token-b", "bob")
        val current = keys.read(prefs)
        assertTrue(keys.setRecognitionEnabled(prefs, true, current))
        assertFalse(keys.setRecognitionEnabled(prefs, false, old))
        assertEquals(true, prefs[keys.recognitionEnabled])
        assertTrue(keys.setRecognitionEnabled(prefs, false, current))
    }
}
