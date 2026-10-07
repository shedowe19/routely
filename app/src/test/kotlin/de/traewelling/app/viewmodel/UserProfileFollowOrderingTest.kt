package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.StatusListResponse
import de.traewelling.app.data.model.User
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UserProfileFollowOrderingTest {
    private val session = AuthSession("https://example.test", "test-only", "current-session")

    @Test fun anOlderProfileReadCannotUndoAConfirmedFollowButKeepsFreshProfileFields() = runTest {
        val gateway = Gateway(user())
        val controller = UserProfileController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadUserProfile("alice"); runCurrent()
        val oldRead = gateway.holdNextRead()
        controller.refresh(); runCurrent()
        controller.toggleFollow(); runCurrent()
        assertEquals(true, controller.uiState.value.user?.following)
        oldRead.complete(Result.success(user(bio = "fresh biography"))); runCurrent()
        assertEquals(true, controller.uiState.value.user?.following)
        assertEquals(false, controller.uiState.value.user?.followPending)
        assertEquals("fresh biography", controller.uiState.value.user?.bio)
        assertFalse(controller.uiState.value.isLoading)
    }

    @Test fun anOlderProfileReadCannotUndoAConfirmedUnfollow() = runTest {
        val gateway = Gateway(user(following = true))
        val controller = UserProfileController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadUserProfile("alice"); runCurrent()
        val oldRead = gateway.holdNextRead()
        controller.refresh(); runCurrent()
        controller.toggleFollow(); runCurrent()
        oldRead.complete(Result.success(user(following = true))); runCurrent()
        assertEquals(1, gateway.unfollowCalls)
        assertEquals(false, controller.uiState.value.user?.following)
        controller.toggleFollow(); runCurrent()
        assertEquals(1, gateway.followCalls)
    }

    @Test fun anOlderReadCannotEraseAPrivateProfileRequestOrOfferAnotherFollow() = runTest {
        val gateway = Gateway(user(privateProfile = true))
        val controller = UserProfileController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadUserProfile("alice"); runCurrent()
        val oldRead = gateway.holdNextRead()
        controller.refresh(); runCurrent()
        controller.toggleFollow(); runCurrent()
        oldRead.complete(Result.success(user(privateProfile = true))); runCurrent()
        assertEquals(false, controller.uiState.value.user?.following)
        assertEquals(true, controller.uiState.value.user?.followPending)
        controller.toggleFollow(); runCurrent()
        assertEquals(1, gateway.followCalls)
        assertEquals(0, gateway.unfollowCalls)
    }

    @Test fun aProfileReadDispatchedAfterConfirmationIsAuthoritative() = runTest {
        val gateway = Gateway(user())
        val controller = UserProfileController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadUserProfile("alice"); runCurrent()
        controller.toggleFollow(); runCurrent()
        assertEquals(true, controller.uiState.value.user?.following)
        gateway.profile = user(following = false)
        controller.refresh(); runCurrent()
        assertEquals(false, controller.uiState.value.user?.following)
    }

    @Test fun aFollowCompletingAfterAProfileRefreshDoesNotRestoreItsCapturedBiography() = runTest {
        val gateway = Gateway(user(bio = "old biography"))
        val controller = UserProfileController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadUserProfile("alice"); runCurrent()
        val oldRead = gateway.holdNextRead()
        val followReply = CompletableDeferred<Result<Unit>>()
        gateway.follow = { followReply.await() }
        controller.refresh(); runCurrent()
        controller.toggleFollow(); runCurrent()
        oldRead.complete(Result.success(user(bio = "fresh biography"))); runCurrent()
        followReply.complete(Result.success(Unit)); runCurrent()
        assertEquals(true, controller.uiState.value.user?.following)
        assertEquals("fresh biography", controller.uiState.value.user?.bio)
    }

    @Test fun aRejectedFollowKeepsItsErrorAfterTheExistingProfileReadCompletes() = runTest {
        val gateway = Gateway(user()).apply {
            follow = { Result.failure(IllegalStateException("request rejected")) }
        }
        val controller = UserProfileController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadUserProfile("alice"); runCurrent()
        val oldRead = gateway.holdNextRead()
        controller.refresh(); runCurrent()
        controller.toggleFollow(); runCurrent()
        val followError = controller.uiState.value.error
        assertNotNull(followError)
        oldRead.complete(Result.success(user())); runCurrent()
        assertEquals(followError, controller.uiState.value.error)
        assertEquals(false, controller.uiState.value.user?.following)
    }

    @Test fun aProfileSwitchDiscardsBothAnOldReadAndItsConfirmedRelationship() = runTest {
        val gateway = Gateway(user())
        val controller = UserProfileController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadUserProfile("alice"); runCurrent()
        val oldRead = gateway.holdNextRead()
        controller.refresh(); runCurrent()
        controller.toggleFollow(); runCurrent()
        gateway.profile = user(id = 6, username = "bob")
        controller.loadUserProfile("bob"); runCurrent()
        oldRead.complete(Result.success(user())); runCurrent()
        assertEquals("bob", controller.uiState.value.user?.username)
        assertEquals(false, controller.uiState.value.user?.following)
    }

    @Test fun anOldSessionsFollowReplyCannotChangeTheDisplayedRelationship() = runTest {
        val gateway = Gateway(user())
        var currentSession = session
        val controller = UserProfileController(backgroundScope, gateway, { currentSession }, MutableSharedFlow())
        runCurrent(); controller.loadUserProfile("alice"); runCurrent()
        val followReply = CompletableDeferred<Result<Unit>>()
        gateway.follow = { followReply.await() }
        controller.toggleFollow(); runCurrent()
        currentSession = session.copy(accessToken = "other-test-only", revision = "new-session")
        followReply.complete(Result.success(Unit)); runCurrent()
        assertEquals(false, controller.uiState.value.user?.following)
    }

    private class Gateway(var profile: User) : UserProfileGateway {
        private var nextRead: CompletableDeferred<Result<User>>? = null
        var follow: suspend () -> Result<Unit> = { Result.success(Unit) }
        var followCalls = 0
        var unfollowCalls = 0
        fun holdNextRead() = CompletableDeferred<Result<User>>().also { nextRead = it }
        override suspend fun getUserProfile(username: String): Result<User> {
            val held = nextRead.also { nextRead = null }
            return if (held != null) withContext(NonCancellable) { held.await() } else Result.success(profile)
        }
        override suspend fun getUserStatuses(username: String, page: Int) =
            Result.success(StatusListResponse(emptyList(), null, null))
        override suspend fun followUser(id: Int): Result<Unit> { ++followCalls; return follow() }
        override suspend fun unfollowUser(id: Int): Result<Unit> { ++unfollowCalls; return follow() }
    }

    companion object {
        private fun user(id: Int = 5, username: String = "alice", following: Boolean = false,
            privateProfile: Boolean = false, bio: String = "biography"): User = Gson().fromJson(
            "{\"id\":$id,\"username\":\"$username\",\"following\":$following," +
                "\"followPending\":false,\"privateProfile\":$privateProfile,\"bio\":\"$bio\"}", User::class.java)
    }
}
