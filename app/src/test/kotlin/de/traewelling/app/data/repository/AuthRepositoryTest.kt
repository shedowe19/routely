package de.traewelling.app.data.repository

import com.google.gson.Gson
import de.traewelling.app.data.api.OAuthApiService
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.model.OAuthTokenResponse
import de.traewelling.app.data.model.UserResponse
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class AuthRepositoryTest {
    private val gson = Gson()
    private val server = "https://example.test"

    @Test fun manualLoginPersistsOnlyAfterValidatedProfileArrives() = runBlocking {
        val store = Store(AuthSession(server, null, "initial"))
        val reply = CompletableDeferred<Response<UserResponse>>()
        val repo = repository(store) { reply.await() }
        val login = async { repo.loginWithToken(server, "new-token") }
        yield()
        assertNull(store.current.accessToken)
        assertEquals(0, store.validatedWrites)
        reply.complete(profile("alice"))
        assertEquals("alice", login.await().getOrThrow())
        assertEquals("new-token", store.current.accessToken)
        assertEquals("alice", store.username)
        assertEquals(1, store.validatedWrites)
    }

    @Test fun failedIncompleteOrEmptyProfileNeverReplacesExistingSession() = runBlocking {
        val replies = listOf(error<UserResponse>(401), Response.success(UserResponse(null)),
            profile(""), Response.success(gson.fromJson("{\"data\":{}}", UserResponse::class.java)))
        for (reply in replies) {
            val before = AuthSession(server, "old-token", "initial")
            val store = Store(before)
            assertTrue(repository(store) { reply }.loginWithToken(server, "new-token").isFailure)
            assertEquals(before, store.current)
            assertEquals(0, store.validatedWrites)
        }
    }

    @Test fun malformedServerReturnsFailureWithoutNetworkOrPersistence() = runBlocking {
        val store = Store(AuthSession(server, null, "initial"))
        var requested = false
        val repo = repository(store) { requested = true; profile("alice") }
        assertTrue(repo.loginWithToken("traewelling.de", "new-token").isFailure)
        assertFalse(requested)
        assertNull(store.current.accessToken)
    }

    @Test fun cancelledLoginDoesNotPersistOrBecomeOrdinaryFailure() = runBlocking {
        val store = Store(AuthSession(server, null, "initial"))
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<UserResponse>>()
        val repo = repository(store) { entered.complete(Unit); reply.await() }
        val login = async { repo.loginWithToken(server, "new-token") }
        entered.await()
        login.cancelAndJoin()
        reply.complete(profile("alice"))
        assertTrue(login.isCancelled)
        assertNull(store.current.accessToken)
    }

    @Test fun startupOutageAndRateLimitPreserveStoredSession() = runBlocking {
        for (code in listOf(429, 500, 503)) {
            val before = AuthSession(server, "old-token", "initial")
            val store = Store(before)
            assertTrue(repository(store) { error(code) }.validateCurrentSession().isFailure)
            assertEquals(before, store.current)
        }
    }

    @Test fun matchingUnauthorizedStartupResponseClearsOnlyItsSession() = runBlocking {
        val store = Store(AuthSession(server, "old-token", "initial"))
        assertNull(repository(store) { error(401) }.validateCurrentSession().getOrThrow())
        assertNull(store.current.accessToken)
    }

    @Test fun oldStartupReplyCannotDeleteOrRenameNewLoginEvenWithSameToken() = runBlocking {
        for (reply in listOf(error<UserResponse>(401), profile("old-user"))) {
            val before = AuthSession(server, "same-token", "initial")
            val store = Store(before)
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Response<UserResponse>>()
            val pending = async {
                repository(store) { entered.complete(Unit); response.await() }.validateCurrentSession()
            }
            entered.await()
            store.saveValidated(server, "same-token", "new-user")
            val newer = store.current
            response.complete(reply)
            assertNull(pending.await().getOrThrow())
            assertEquals(newer, store.current)
            assertEquals("new-user", store.username)
        }
    }

    @Test fun localLogoutPrecedesNetworkAndLateCompletionPreservesNewLogin() = runBlocking {
        val store = Store(AuthSession(server, "old-token", "initial"))
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Response<Unit>>()
        val api = object : TraewellingApiService by unusedService() {
            override suspend fun logout(): Response<Unit> {
                assertNull(store.current.accessToken)
                entered.complete(Unit)
                return response.await()
            }
        }
        val pending = async { AuthRepository(store, { _, _ -> api }).logout() }
        entered.await()
        store.saveValidated(server, "new-token", "new-user")
        val newer = store.current
        response.complete(Response.success(Unit))
        assertTrue(pending.await().isSuccess)
        assertEquals(newer, store.current)
    }

    @Test fun localLogoutPersistenceFailureKeepsSessionAndReportsFailure() = runBlocking {
        val before = AuthSession(server, "old-token", "initial")
        val store = Store(before)
        val failingStore = object : AuthSessionStore by store {
            override suspend fun clearIfMatches(expected: AuthSession): Boolean {
                throw java.io.IOException("local persistence unavailable")
            }
        }
        val repo = AuthRepository(failingStore, { _, _ -> throw AssertionError("Network before local logout") })
        assertTrue(repo.logout().isFailure)
        assertEquals(before, store.current)
    }

    @Test fun missingOAuthAccessTokenNeverCreatesSession() = runBlocking {
        for (token in listOf(null, "", " ")) {
            val before = AuthSession(server, null, "initial")
            val store = Store(before)
            val oauth = object : OAuthApiService by unusedService() {
                override suspend fun exchangeToken(grantType: String, clientId: String, clientSecret: String?,
                    redirectUri: String, code: String, codeVerifier: String?) =
                    Response.success(OAuthTokenResponse(token, "refresh", "Bearer", 3600))
            }
            val repo = AuthRepository(store, oauthFactory = { oauth })
            assertTrue(repo.exchangeCodeForToken(server, "client", null, "code", "verifier").isFailure)
            assertEquals(before, store.current)
        }
    }

    @Test fun lateRefreshCannotRestoreLoggedOutOrDifferentAccount() = runBlocking {
        val store = Store(AuthSession(server, "old-token", "initial"))
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Response<OAuthTokenResponse>>()
        val oauth = object : OAuthApiService by unusedService() {
            override suspend fun refreshToken(grantType: String, clientId: String, clientSecret: String?,
                refreshToken: String): Response<OAuthTokenResponse> {
                entered.complete(Unit)
                return response.await()
            }
        }
        val pending = async { AuthRepository(store, oauthFactory = { oauth }).refreshAccessToken() }
        entered.await()
        store.saveValidated(server, "new-account-token", "new-user")
        val newer = store.current
        response.complete(Response.success(OAuthTokenResponse("refreshed-old-token", null, "Bearer", 3600)))
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(newer, store.current)
    }

    @Test fun refreshWithoutAReplacementRetainsTheTokenForTheNextRefresh() = runBlocking {
        for (replacement in listOf(null, "", " ")) {
            val store = Store(AuthSession(server, "old-token", "initial"))
            val sentRefreshTokens = mutableListOf<String>()
            val repo = refreshRepository(store) { sent ->
                sentRefreshTokens += sent
                Response.success(OAuthTokenResponse("new-access", replacement, "Bearer", 3600))
            }
            repo.refreshAccessToken().getOrThrow()
            assertEquals("new-access", store.current.accessToken)
            assertEquals("refresh", store.refreshToken)
            repo.refreshAccessToken().getOrThrow()
            assertEquals(listOf("refresh", "refresh"), sentRefreshTokens)
        }
    }

    @Test fun refreshWithAReplacementRotatesTheTokenUsedByTheNextRequest() = runBlocking {
        val store = Store(AuthSession(server, "old-token", "initial"))
        val sentRefreshTokens = mutableListOf<String>()
        val repo = refreshRepository(store) { sent ->
            sentRefreshTokens += sent
            Response.success(OAuthTokenResponse("new-access", "replacement", "Bearer", 3600))
        }
        repo.refreshAccessToken().getOrThrow()
        assertEquals("replacement", store.refreshToken)
        repo.refreshAccessToken().getOrThrow()
        assertEquals(listOf("refresh", "replacement"), sentRefreshTokens)
    }

    @Test fun incompleteRefreshSuccessNeverChangesEitherCredential() = runBlocking {
        val replies = listOf(Response.success<OAuthTokenResponse>(null)) + listOf(null, "", " ").map { token ->
            Response.success(OAuthTokenResponse(token, "replacement", "Bearer", 3600))
        }
        for (reply in replies) {
            val before = AuthSession(server, "old-token", "initial")
            val store = Store(before)
            assertTrue(refreshRepository(store) { reply }.refreshAccessToken().isFailure)
            assertEquals(before, store.current)
            assertEquals("refresh", store.refreshToken)
        }
    }

    @Test fun clientRequestAndScopeErrorsDoNotDeleteTheUserSession() = runBlocking {
        for (code in listOf(400, 401, 403)) {
            for (oauthError in listOf("invalid_client", "invalid_request", "invalid_scope", "unauthorized_client")) {
                val before = AuthSession(server, "old-token", "initial")
                val store = Store(before)
                val reply = error<OAuthTokenResponse>(code, "{\"error\":\"$oauthError\"}")
                assertTrue(refreshRepository(store) { reply }.refreshAccessToken().isFailure)
                assertEquals(before, store.current)
                assertEquals("refresh", store.refreshToken)
            }
        }
    }

    @Test fun missingMalformedAmbiguousOrOversizedOAuthErrorsCannotDeleteCredentials() = runBlocking {
        val bodies = listOf("", "{}", "not JSON", "{error:'invalid_grant'}",
            "{\"error\":null}", "{\"error\":400}", "{\"error\":{\"code\":\"invalid_grant\"}}",
            "{\"error\":\"invalid_grant\",\"error\":\"invalid_client\"}",
            "{\"error\":\"invalid_grant\"} trailing",
            "{\"error\":\"invalid_grant\",\"error_description\":\"${"x".repeat(16 * 1024)}\"}")
        for (code in listOf(400, 401, 403)) {
            for (body in bodies) {
                val before = AuthSession(server, "old-token", "initial")
                val store = Store(before)
                assertTrue(refreshRepository(store) { error(code, body) }.refreshAccessToken().isFailure)
                assertEquals(before, store.current)
                assertEquals("refresh", store.refreshToken)
            }
        }
        val store = Store(AuthSession(server, "old-token", "initial"))
        val before = store.current
        val invalidUtf8 = "{\"error\":\"invalid_grant\",\"description\":\"".toByteArray() +
            byteArrayOf(0x80.toByte()) + "\"}".toByteArray()
        val reply = Response.error<OAuthTokenResponse>(400, invalidUtf8.toResponseBody())
        assertTrue(refreshRepository(store) { reply }.refreshAccessToken().isFailure)
        assertEquals(before, store.current)
    }

    @Test fun explicitInvalidGrantClearsOnlyTheMatchingCredentials() = runBlocking {
        for (code in listOf(400, 401, 403)) {
            val store = Store(AuthSession(server, "old-token", "initial"))
            val reply = error<OAuthTokenResponse>(code, "{\"error\":\"invalid_grant\"}")
            assertTrue(refreshRepository(store) { reply }.refreshAccessToken().isFailure)
            assertNull(store.current.accessToken)
            assertNull(store.refreshToken)
        }
    }

    @Test fun outagesAndRateLimitsCannotClearCredentialsEvenWithAnOAuthErrorBody() = runBlocking {
        for (code in listOf(408, 429, 500, 503)) {
            val before = AuthSession(server, "old-token", "initial")
            val store = Store(before)
            val reply = error<OAuthTokenResponse>(code, "{\"error\":\"invalid_grant\"}")
            assertTrue(refreshRepository(store) { reply }.refreshAccessToken().isFailure)
            assertEquals(before, store.current)
            assertEquals("refresh", store.refreshToken)
        }
    }

    @Test fun lateInvalidGrantCannotDeleteANewerSessionEvenWithTheSameAccessToken() = runBlocking {
        val store = Store(AuthSession(server, "same-token", "initial"))
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<OAuthTokenResponse>>()
        val repo = refreshRepository(store) { entered.complete(Unit); reply.await() }
        val pending = async { repo.refreshAccessToken() }
        entered.await()
        store.saveValidated(server, "same-token", "new-user")
        val newer = store.current
        reply.complete(error(400, "{\"error\":\"invalid_grant\"}"))
        assertTrue(pending.await().isFailure)
        assertEquals(newer, store.current)
        assertEquals("new-user", store.username)
    }

    @Test fun cancellationImmediatelyBeforeARefreshReplyCannotWriteOrClearCredentials() = runBlocking {
        for (reply in listOf(Response.success(OAuthTokenResponse("new-access", "replacement", "Bearer", 3600)),
            error<OAuthTokenResponse>(400, "{\"error\":\"invalid_grant\"}"))) {
            val before = AuthSession(server, "old-token", "initial")
            val store = Store(before)
            val repo = refreshRepository(store) {
                currentCoroutineContext().cancel()
                reply
            }
            val pending = async { repo.refreshAccessToken() }
            pending.join()
            assertTrue(pending.isCancelled)
            assertEquals(before, store.current)
            assertEquals("refresh", store.refreshToken)
        }
    }

    private fun refreshRepository(store: Store, response: suspend (String) -> Response<OAuthTokenResponse>): AuthRepository {
        val oauth = object : OAuthApiService by unusedService() {
            override suspend fun refreshToken(grantType: String, clientId: String, clientSecret: String?,
                refreshToken: String) = response(refreshToken)
        }
        return AuthRepository(store, oauthFactory = { oauth })
    }

    private fun repository(store: Store, response: suspend () -> Response<UserResponse>): AuthRepository {
        val api = object : TraewellingApiService by unusedService() {
            override suspend fun getAuthUser() = response()
        }
        return AuthRepository(store, { _, _ -> api })
    }

    private fun profile(username: String): Response<UserResponse> = Response.success(
        gson.fromJson("{\"data\":{\"username\":\"$username\"}}", UserResponse::class.java))

    private fun <T> error(code: Int, body: String = "{}"): Response<T> = Response.error(code, body.toResponseBody())

    internal class Store(var current: AuthSession) : AuthSessionStore {
        var username: String? = null
        var refreshToken: String? = "refresh"
        var validatedWrites = 0
        private var revision = 0
        override suspend fun session() = current
        override suspend fun saveValidated(serverUrl: String, accessToken: String, username: String) {
            current = AuthSession(serverUrl, accessToken, "revision-${++revision}")
            this.username = username
            refreshToken = null
            validatedWrites++
        }
        override suspend fun clearIfMatches(expected: AuthSession): Boolean {
            if (current != expected) return false
            current = current.copy(accessToken = null, revision = "revision-${++revision}")
            username = null
            refreshToken = null
            return true
        }
        override suspend fun saveUsernameIfMatches(expected: AuthSession, username: String): Boolean {
            if (current != expected) return false
            this.username = username
            return true
        }
        override suspend fun saveTokensIfMatches(expected: AuthSession, token: String, refresh: String?): Boolean {
            if (current != expected) return false
            current = current.copy(accessToken = token, revision = "revision-${++revision}")
            refreshToken = refresh
            return true
        }
        override suspend fun refreshCredentials() = RefreshCredentials(current, "client", null, refreshToken)
    }
}

internal inline fun <reified T> unusedService(): T = Proxy.newProxyInstance(
    T::class.java.classLoader, arrayOf(T::class.java)
) { _, method, _ -> throw AssertionError("Unexpected API call: ${method.name}") } as T
