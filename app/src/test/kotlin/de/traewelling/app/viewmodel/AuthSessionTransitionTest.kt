package de.traewelling.app.viewmodel

import de.traewelling.app.util.AuthSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionTransitionTest {

    private val loggedIn = AuthUiState(
        hasLoadedSession = true,
        isLoggedIn = true,
        serverUrl = "https://example.org",
        sessionRevision = "session-a"
    )

    @Test
    fun unexpectedSignOutExplainsTheExpiredSession() {
        val next = loggedIn.afterAuthSession(
            AuthSession("https://example.org", null, "session-b"),
            logoutRequested = false
        )

        assertFalse(next.isLoggedIn)
        assertEquals("", next.accessToken)
        assertEquals(SESSION_EXPIRED_MESSAGE, next.error)
    }

    @Test
    fun intentionalLogoutStaysSilent() {
        val next = loggedIn.afterAuthSession(
            AuthSession("https://example.org", null, "session-b"),
            logoutRequested = true
        )

        assertFalse(next.isLoggedIn)
        assertNull(next.error)
    }

    @Test
    fun initialSignedOutStateHasNoExpiryMessage() {
        val next = AuthUiState().afterAuthSession(
            AuthSession("https://example.org", null, "session-b"),
            logoutRequested = false
        )

        assertFalse(next.isLoggedIn)
        assertNull(next.error)
    }

    @Test
    fun refreshedTokenKeepsTheLoginAndItsExistingError() {
        val next = loggedIn.copy(error = "Netzwerkfehler").afterAuthSession(
            AuthSession("https://example.org", "new-token", "session-b"),
            logoutRequested = false
        )

        assertTrue(next.isLoggedIn)
        assertEquals("Netzwerkfehler", next.error)
    }
}
