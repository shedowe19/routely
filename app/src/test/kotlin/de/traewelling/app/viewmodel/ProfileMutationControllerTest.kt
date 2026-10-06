package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.*
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Both production controllers are exercised; Android ViewModels only adapt their gateways. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileMutationControllerTest {
    @Test fun loadedProfilesAdoptEditDeleteAndLikeWithoutAddingAnUnknownCard() = runTest {
        for (kind in Kind.entries) {
            val harness = Harness(backgroundScope, kind)
            runCurrent(); harness.load(); runCurrent()
            harness.events.emit(StatusMutation.Updated(harness.gateway.session.revision, status(1, "edited")))
            runCurrent()
            assertEquals("edited", harness.statuses.first().body)
            harness.events.emit(StatusMutation.Updated(harness.gateway.session.revision, status(9, "unknown")))
            runCurrent()
            assertEquals(listOf(1, 2), harness.statuses.map { it.id })
            harness.events.emit(StatusMutation.LikeChanged(harness.gateway.session.revision, 1, true)); runCurrent()
            assertEquals(true, harness.statuses.first().liked)
            assertEquals(1, harness.statuses.first().likes)
            harness.events.emit(StatusMutation.Deleted(harness.gateway.session.revision, 1)); runCurrent()
            assertEquals(listOf(2), harness.statuses.map { it.id })
        }
    }

    @Test fun oldProfileGetCannotResurrectADeletedCardAfterTheMutation() = runTest {
        for (kind in Kind.entries) {
            val harness = Harness(backgroundScope, kind)
            runCurrent(); harness.load(); runCurrent()
            val oldGet = CompletableDeferred<Result<StatusListResponse>>()
            harness.gateway.statuses = { _, _ -> withContext(NonCancellable) { oldGet.await() } }
            harness.load(refresh = true); runCurrent()
            val freshGet = CompletableDeferred<Result<StatusListResponse>>()
            harness.gateway.statuses = { _, _ -> freshGet.await() }
            harness.events.emit(StatusMutation.Deleted(harness.gateway.session.revision, 1)); runCurrent()
            assertEquals(listOf(2), harness.statuses.map { it.id })
            oldGet.complete(Result.success(response(status(1), status(2)))); runCurrent()
            assertFalse(harness.statuses.any { it.id == 1 })
            freshGet.complete(Result.success(response(status(2)))); runCurrent()
            assertEquals(listOf(2), harness.statuses.map { it.id })
            assertFalse(harness.isLoading)
        }
    }

    @Test fun followingUpdatedEventRestartsAnInvalidatedCardsHeldVerificationGet() = runTest {
        for (kind in Kind.entries) {
            val harness = Harness(backgroundScope, kind)
            runCurrent(); harness.load(); runCurrent()
            val oldGet = CompletableDeferred<Result<StatusListResponse>>()
            harness.gateway.statuses = { _, _ -> withContext(NonCancellable) { oldGet.await() } }
            harness.events.emit(StatusMutation.Invalidated(harness.gateway.session.revision, 1)); runCurrent()
            assertFalse(harness.statuses.any { it.id == 1 })
            val freshGet = CompletableDeferred<Result<StatusListResponse>>()
            harness.gateway.statuses = { _, _ -> freshGet.await() }
            harness.events.emit(StatusMutation.Updated(harness.gateway.session.revision, status(1, "verified")))
            runCurrent()
            assertTrue(harness.isLoading)
            oldGet.complete(Result.success(response(status(1, "stale"), status(2)))); runCurrent()
            assertFalse(harness.statuses.any { it.id == 1 })
            freshGet.complete(Result.success(response(status(1, "verified"), status(2)))); runCurrent()
            assertEquals("verified", harness.statuses.first().body)
        }
    }

    @Test fun oldSessionEventsAndLateGetsCannotUpdateTheBoundProfile() = runTest {
        for (kind in Kind.entries) {
            val harness = Harness(backgroundScope, kind)
            runCurrent(); harness.load(); runCurrent()
            val originalSession = harness.gateway.session
            val heldGet = CompletableDeferred<Result<StatusListResponse>>()
            harness.gateway.statuses = { _, _ -> heldGet.await() }
            harness.load(refresh = true); runCurrent()
            harness.gateway.session = originalSession.copy(revision = "new-login", accessToken = "other-test-token")
            harness.events.emit(StatusMutation.Deleted(originalSession.revision, 1))
            harness.events.emit(StatusMutation.Updated(harness.gateway.session.revision, status(1, "another account")))
            runCurrent()
            heldGet.complete(Result.success(response(status(1, "old reply")))); runCurrent()
            assertEquals("original", harness.statuses.first().body)
        }
    }

    @Test fun cachedOwnUsernameProfileDoesNotRestoreOldCardsWhenReopened() = runTest {
        val harness = Harness(backgroundScope, Kind.USER)
        runCurrent(); harness.load(); runCurrent()
        harness.events.emit(StatusMutation.Updated(harness.gateway.session.revision, status(1, "saved")))
        runCurrent()
        val requestsBeforeReturn = harness.gateway.statusCalls
        harness.load(); runCurrent()
        assertEquals(requestsBeforeReturn, harness.gateway.statusCalls)
        assertEquals("saved", harness.statuses.first().body)
        harness.events.emit(StatusMutation.Deleted(harness.gateway.session.revision, 1)); runCurrent()
        harness.load(); runCurrent()
        assertEquals(listOf(2), harness.statuses.map { it.id })
    }

    @Test fun aCardMutationDuringFollowKeepsTheSubmittedActionAndItsNewCard() = runTest {
        val harness = Harness(backgroundScope, Kind.USER)
        runCurrent(); harness.load(); runCurrent()
        val follow = CompletableDeferred<Result<Unit>>()
        harness.gateway.follow = { follow.await() }
        harness.other!!.toggleFollow(); runCurrent()
        assertTrue(harness.other.uiState.value.isFollowLoading)
        harness.events.emit(StatusMutation.Updated(harness.gateway.session.revision, status(1, "saved")))
        runCurrent()
        assertTrue(harness.other.uiState.value.isFollowLoading)
        assertEquals("saved", harness.statuses.first().body)
        follow.complete(Result.success(Unit)); runCurrent()
        assertFalse(harness.other.uiState.value.isFollowLoading)
        assertEquals(true, harness.other.uiState.value.user?.following)
        assertEquals("saved", harness.statuses.first().body)
    }

    @Test fun bodylessVerificationWaitsForFollowAndThenLoadsTheAuthoritativeProfile() = runTest {
        val harness = Harness(backgroundScope, Kind.USER)
        runCurrent(); harness.load(); runCurrent()
        val follow = CompletableDeferred<Result<Unit>>()
        harness.gateway.follow = { follow.await() }
        harness.other!!.toggleFollow(); runCurrent()
        val countBeforeInvalidation = harness.gateway.statusCalls
        harness.events.emit(StatusMutation.Invalidated(harness.gateway.session.revision, 1)); runCurrent()
        assertTrue(harness.other.uiState.value.isFollowLoading)
        assertEquals(countBeforeInvalidation, harness.gateway.statusCalls)
        assertFalse(harness.statuses.any { it.id == 1 })
        harness.gateway.profile = user(following = true)
        harness.gateway.statuses = { _, _ -> Result.success(response(status(1, "verified"), status(2))) }
        follow.complete(Result.success(Unit)); runCurrent()
        assertFalse(harness.other.uiState.value.isFollowLoading)
        assertEquals(true, harness.other.uiState.value.user?.following)
        assertEquals("verified", harness.statuses.first().body)
        assertEquals(countBeforeInvalidation + 1, harness.gateway.statusCalls)
    }

    @Test fun sameProfileRefreshWaitsForTheSubmittedFollowInsteadOfCancellingIt() = runTest {
        val harness = Harness(backgroundScope, Kind.USER)
        runCurrent(); harness.load(); runCurrent()
        val follow = CompletableDeferred<Result<Unit>>()
        harness.gateway.follow = { follow.await() }
        harness.other!!.toggleFollow(); runCurrent()
        val requestsBeforeRefresh = harness.gateway.statusCalls
        harness.load(refresh = true); runCurrent()
        assertTrue(harness.other.uiState.value.isFollowLoading)
        assertEquals(requestsBeforeRefresh, harness.gateway.statusCalls)
        harness.gateway.profile = user(following = true)
        follow.complete(Result.success(Unit)); runCurrent()
        assertFalse(harness.other.uiState.value.isFollowLoading)
        assertEquals(true, harness.other.uiState.value.user?.following)
        assertEquals(requestsBeforeRefresh + 1, harness.gateway.statusCalls)
    }

    @Test fun automaticCardVerificationAndManualRefreshCannotHideAFailedFollow() = runTest {
        val harness = Harness(backgroundScope, Kind.USER)
        runCurrent(); harness.load(); runCurrent()
        val follow = CompletableDeferred<Result<Unit>>()
        harness.gateway.follow = { follow.await() }
        harness.other!!.toggleFollow(); runCurrent()
        harness.events.emit(StatusMutation.Invalidated(harness.gateway.session.revision, 1)); runCurrent()
        harness.gateway.statuses = { _, _ -> Result.success(response(status(1, "verified"), status(2))) }
        follow.complete(Result.failure(IllegalStateException("rejected"))); runCurrent()
        assertFalse(harness.other.uiState.value.isFollowLoading)
        assertEquals("verified", harness.statuses.first().body)
        assertTrue(harness.error!!.contains("Folgen konnte nicht geändert werden"))
        val followError = harness.error
        harness.load(refresh = true); runCurrent()
        assertEquals(followError, harness.error)
        harness.other.clearError()
        assertNull(harness.error)
        harness.load(refresh = true); runCurrent()
        assertNull(harness.error)
    }

    @Test fun failedVerificationKeepsTheOtherCardsAndCanBeRetried() = runTest {
        for (kind in Kind.entries) {
            val harness = Harness(backgroundScope, kind)
            runCurrent(); harness.load(); runCurrent()
            harness.gateway.statuses = { _, _ -> Result.failure(IllegalStateException("temporary failure")) }
            harness.events.emit(StatusMutation.Invalidated(harness.gateway.session.revision, 1)); runCurrent()
            assertEquals(listOf(2), harness.statuses.map { it.id })
            assertNotNull(harness.error)
            harness.gateway.statuses = { _, _ -> Result.success(response(status(1, "verified"), status(2))) }
            harness.load(refresh = true); runCurrent()
            assertEquals("verified", harness.statuses.first().body)
            assertFalse(harness.isLoading)
        }
    }

    @Test fun anOldUsersPaginationCannotOverwriteTheNewUsersProfile() = runTest {
        val harness = Harness(backgroundScope, Kind.USER)
        harness.gateway.statuses = { _, _ -> Result.success(response(status(1), next = "page2")) }
        runCurrent(); harness.load(); runCurrent()
        val heldGet = CompletableDeferred<Result<StatusListResponse>>()
        harness.gateway.statuses = { _, _ -> withContext(NonCancellable) { heldGet.await() } }
        harness.other!!.loadMoreStatuses(); runCurrent()
        harness.gateway.profile = user(username = "second")
        harness.gateway.statuses = { _, _ -> Result.success(response(status(7))) }
        harness.other.loadUserProfile("second"); runCurrent()
        heldGet.complete(Result.success(response(status(2)))); runCurrent()
        assertEquals("second", harness.other.uiState.value.user?.username)
        assertEquals(listOf(7), harness.statuses.map { it.id })
    }

    private enum class Kind { OWN, USER }
    private class Harness(scope: CoroutineScope, kind: Kind) {
        val gateway = Gateway()
        val events = MutableSharedFlow<StatusMutation>()
        val own = if (kind == Kind.OWN) ProfileController(scope, gateway, { gateway.session }, events) else null
        val other = if (kind == Kind.USER) UserProfileController(scope, gateway, { gateway.session }, events) else null
        val statuses get() = own?.uiState?.value?.recentStatuses ?: other!!.uiState.value.statuses
        val isLoading get() = own?.uiState?.value?.isLoading ?: other!!.uiState.value.isLoading
        val error get() = if (own != null) own.uiState.value.error else other!!.uiState.value.error
        fun load(refresh: Boolean = false) {
            if (own != null) own.loadProfile(refresh) else other!!.loadUserProfile("alice", refresh)
        }
    }

    private class Gateway : ProfileGateway, UserProfileGateway {
        var session = AuthSession("https://example.test", "synthetic-test-token", "login")
        var profile = user()
        var statuses: suspend (String, Int) -> Result<StatusListResponse> = { _, _ -> Result.success(response(status(1), status(2))) }
        var follow: suspend () -> Result<Unit> = { Result.success(Unit) }
        var statusCalls = 0
        override suspend fun getCurrentUser() = Result.success(profile)
        override suspend fun getUserProfile(username: String) = Result.success(profile)
        override suspend fun getStatistics(): Result<StatisticsData> = Result.success(Gson().fromJson("{}", StatisticsData::class.java))
        override suspend fun getUserStatuses(username: String, page: Int): Result<StatusListResponse> { ++statusCalls; return statuses(username, page) }
        override suspend fun followUser(id: Int) = follow()
        override suspend fun unfollowUser(id: Int) = follow()
    }

    companion object {
        private fun status(id: Int, body: String = "original"): Status = Gson().fromJson(
            "{\"id\":$id,\"body\":\"$body\",\"liked\":false,\"likes\":0}", Status::class.java)
        private fun user(username: String = "alice", following: Boolean = false): User = Gson().fromJson(
            "{\"id\":5,\"username\":\"$username\",\"following\":$following,\"privateProfile\":false}", User::class.java)
        private fun response(vararg statuses: Status, next: String? = null) = StatusListResponse(statuses.toList(),
            next?.let { PaginationLinks(null, null, null, it) }, null)
    }
}
