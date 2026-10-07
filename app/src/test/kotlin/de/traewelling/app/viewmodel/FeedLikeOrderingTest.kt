package de.traewelling.app.viewmodel

import com.google.gson.Gson
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StatusListResponse
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request/intent ordering regressions use the real controller and delayed gateways. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedLikeOrderingTest {
    private val session = AuthSession("https://example.test", "test-only", "review-session")

    @Test fun aGetStartedBeforeASuccessfulLikeMustNotRevertTheConfirmedLike() = runTest {
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, MutableSharedFlow<StatusMutation>())
        runCurrent()
        controller.loadFeed()
        runCurrent()
        val staleGet = CompletableDeferred<Result<StatusListResponse>>()
        val freshGet = CompletableDeferred<Result<StatusListResponse>>()
        var reads = 0
        gateway.dashboard = {
            if (++reads == 1) withContext(NonCancellable) { staleGet.await() }
            else freshGet.await()
        }
        controller.refresh()
        runCurrent()
        controller.likeStatus(1)
        runCurrent()
        assertEquals(1, gateway.likeCalls)
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        assertEquals("A confirmed Like must restart the interrupted read", 2, reads)
        staleGet.complete(Result.success(response()))
        runCurrent()
        assertTrue("A GET begun before the successful Like must preserve liked=true", controller.uiState.value.statuses.single().liked == true)
        assertTrue(controller.uiState.value.isRefreshing)
        freshGet.complete(Result.success(response(liked = true, likes = 5)))
        runCurrent()
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        assertEquals("The fresh server snapshot supplies the authoritative count", 5,
            controller.uiState.value.statuses.single().likes)
    }

    @Test fun aGetCompletingDuringALikeMustNotMakeTheNextTapSendAnotherLike() = runTest {
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, MutableSharedFlow<StatusMutation>())
        runCurrent()
        controller.loadFeed()
        runCurrent()
        val staleGet = CompletableDeferred<Result<StatusListResponse>>()
        val pendingLike = CompletableDeferred<Result<Unit>>()
        gateway.dashboard = { staleGet.await() }
        gateway.like = { pendingLike.await() }
        controller.refresh()
        runCurrent()
        controller.likeStatus(1)
        runCurrent()
        staleGet.complete(Result.success(response()))
        runCurrent()
        pendingLike.complete(Result.success(Unit))
        runCurrent()
        controller.likeStatus(1)
        runCurrent()
        assertEquals("Second tap after a committed Like must issue Unlike", 1, gateway.unlikeCalls)
        assertEquals(1, gateway.likeCalls)
    }

    @Test fun aGetStartedAfterConfirmationIsAuthoritativeEvenWhenAnotherClientChangedTheLike() = runTest {
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, MutableSharedFlow<StatusMutation>())
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(1); runCurrent()
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        gateway.dashboard = { Result.success(response(liked = false, likes = 7)) }
        controller.refresh(); runCurrent()
        assertEquals(false, controller.uiState.value.statuses.single().liked)
        assertEquals(7, controller.uiState.value.statuses.single().likes)
    }

    @Test fun aPendingGetKeepsTheLikeAndItsFailureRollsBackToTheLatestServerCount() = runTest {
        val gateway = Gateway()
        val pendingLike = CompletableDeferred<Result<Unit>>()
        gateway.like = { pendingLike.await() }
        val controller = FeedController(backgroundScope, gateway, { session }, MutableSharedFlow<StatusMutation>())
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(1); runCurrent()
        gateway.dashboard = { Result.success(response(liked = false, likes = 10)) }
        controller.refresh(); runCurrent()
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        assertEquals(11, controller.uiState.value.statuses.single().likes)
        pendingLike.complete(Result.failure(IllegalStateException("write failed"))); runCurrent()
        assertEquals(false, controller.uiState.value.statuses.single().liked)
        assertEquals(10, controller.uiState.value.statuses.single().likes)
    }

    @Test fun aQueuedFullStatusEventCannotEraseTheAlreadyConfirmedLike() = runTest {
        val gateway = Gateway()
        val events = MutableSharedFlow<StatusMutation>()
        val controller = FeedController(backgroundScope, gateway, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(1); runCurrent()
        events.emit(StatusMutation.Updated(session.revision, response(body = "edited").data!!.single()))
        runCurrent()
        assertEquals("edited", controller.uiState.value.statuses.single().body)
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        gateway.dashboard = { Result.success(response(liked = true, likes = 5, body = "edited")) }
        controller.refresh(); runCurrent()
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        assertEquals(5, controller.uiState.value.statuses.single().likes)
    }

    @Test fun switchingFeedsDuringSubmissionRetainsTheIntentAndPreventsADuplicateLike() = runTest {
        val gateway = Gateway()
        val pendingLike = CompletableDeferred<Result<Unit>>()
        gateway.like = { pendingLike.await() }
        val controller = FeedController(backgroundScope, gateway, { session }, MutableSharedFlow<StatusMutation>())
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(1); runCurrent()
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        controller.likeStatus(1); runCurrent()
        assertEquals(1, gateway.likeCalls)
        pendingLike.complete(Result.success(Unit)); runCurrent()
        controller.likeStatus(1); runCurrent()
        assertEquals(1, gateway.unlikeCalls)
        assertEquals(1, gateway.likeCalls)
    }

    @Test fun aFollowingVerifiedPutRestartsVerificationOfABodylessAcceptedPut() = runTest {
        val gateway = Gateway()
        val events = MutableSharedFlow<StatusMutation>()
        val controller = FeedController(backgroundScope, gateway, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        val oldVerification = CompletableDeferred<Result<StatusListResponse>>()
        gateway.dashboard = { withContext(NonCancellable) { oldVerification.await() } }
        events.emit(StatusMutation.Invalidated(session.revision, 1)); runCurrent()
        assertTrue(controller.uiState.value.statuses.isEmpty())
        assertTrue(controller.uiState.value.isRefreshing)

        val newVerification = CompletableDeferred<Result<StatusListResponse>>()
        gateway.dashboard = { newVerification.await() }
        events.emit(StatusMutation.Updated(session.revision, response(body = "new text").data!!.single()))
        runCurrent()
        assertTrue("A later update must keep verification running", controller.uiState.value.isRefreshing)
        oldVerification.complete(Result.success(response(body = "stale"))); runCurrent()
        assertTrue(controller.uiState.value.statuses.isEmpty())
        newVerification.complete(Result.success(response(body = "new text"))); runCurrent()
        assertEquals("new text", controller.uiState.value.statuses.single().body)
    }

    private class Gateway : FeedGateway {
        var dashboard: suspend () -> Result<StatusListResponse> = { Result.success(response()) }
        var like: suspend () -> Result<Unit> = { Result.success(Unit) }
        var likeCalls = 0
        var unlikeCalls = 0
        override suspend fun getDashboard(page: Int) = dashboard()
        override suspend fun getGlobalFeed(page: Int) = dashboard()
        override suspend fun likeStatus(id: Int): Result<Unit> { ++likeCalls; return like() }
        override suspend fun unlikeStatus(id: Int): Result<Unit> { ++unlikeCalls; return Result.success(Unit) }
    }

    companion object {
        private fun response(liked: Boolean = false, likes: Int = 0, body: String = "original") =
            StatusListResponse(listOf(Gson().fromJson(
                "{\"id\":1,\"body\":\"$body\",\"liked\":$liked,\"likes\":$likes,\"isLikable\":true}", Status::class.java)), null, null)
    }
}
