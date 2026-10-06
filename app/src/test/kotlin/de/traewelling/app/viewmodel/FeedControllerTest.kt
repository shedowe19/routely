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
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedControllerTest {
    private val session = AuthSession("https://example.test", "test-only", "current-revision")

    @Test fun deletionRemovesOnlyTheMatchingLoadedCardAndLateGetCannotRestoreIt() = runTest {
        val events = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)
        val api = Gateway().apply { dashboard = { Result.success(response(status(1), status(2))) } }
        val controller = FeedController(backgroundScope, api, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        val oldReply = CompletableDeferred<Result<StatusListResponse>>()
        api.dashboard = { withContext(NonCancellable) { oldReply.await() } }
        controller.refresh(); runCurrent()
        events.emit(StatusMutation.Deleted(session.revision, 1)); runCurrent()
        assertEquals(listOf(2), controller.uiState.value.statuses.map { it.id })
        assertFalse(controller.uiState.value.isRefreshing)
        oldReply.complete(Result.success(response(status(1), status(2)))); runCurrent()
        assertEquals(listOf(2), controller.uiState.value.statuses.map { it.id })
    }

    @Test fun updatedCardReplacesItsLoadedSnapshotAndDoesNotInsertAnUnknownCard() = runTest {
        val events = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)
        val controller = FeedController(backgroundScope, Gateway(), { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        events.emit(StatusMutation.Updated(session.revision, status(1, "edited"))); runCurrent()
        events.emit(StatusMutation.Updated(session.revision, status(9, "not in this feed"))); runCurrent()
        assertEquals(listOf("edited"), controller.uiState.value.statuses.map { it.body })
    }

    @Test fun anotherSessionCannotUpdateOrDeleteCardsEvenWithTheSameNumericIds() = runTest {
        val events = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)
        var current = session
        val controller = FeedController(backgroundScope, Gateway(), { current }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        events.emit(StatusMutation.Deleted("different-account-revision", 1)); runCurrent()
        assertEquals(1, controller.uiState.value.statuses.size)
        current = session.copy(revision = "different-account-revision", accessToken = "another-test-only")
        events.emit(StatusMutation.Updated(current.revision, status(1, "other account"))); runCurrent()
        events.emit(StatusMutation.Deleted(session.revision, 1)); runCurrent()
        assertEquals("original", controller.uiState.value.statuses.single().body)
    }

    @Test fun aCardUpdateCannotEraseAnInFlightLike() = runTest {
        val events = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)
        val put = CompletableDeferred<Result<Unit>>()
        val api = Gateway().apply { like = { put.await() } }
        val controller = FeedController(backgroundScope, api, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(1); runCurrent()
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        events.emit(StatusMutation.Updated(session.revision, status(1, "edited"))); runCurrent()
        assertEquals("edited", controller.uiState.value.statuses.single().body)
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        put.complete(Result.failure(IllegalStateException("like failed"))); runCurrent()
        assertEquals(false, controller.uiState.value.statuses.single().liked)
        assertEquals("edited", controller.uiState.value.statuses.single().body)
    }

    @Test fun failedLikeCannotRestoreADeletedCard() = runTest {
        val events = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)
        val put = CompletableDeferred<Result<Unit>>()
        val api = Gateway().apply { like = { put.await() } }
        val controller = FeedController(backgroundScope, api, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(1); runCurrent()
        events.emit(StatusMutation.Deleted(session.revision, 1)); runCurrent()
        put.complete(Result.failure(IllegalStateException("like failed"))); runCurrent()
        assertTrue(controller.uiState.value.statuses.isEmpty())
    }

    @Test fun aDeletedUnloadedStatusStillInvalidatesAnOlderFetchThatWouldContainIt() = runTest {
        val events = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)
        val oldReply = CompletableDeferred<Result<StatusListResponse>>()
        val api = Gateway().apply { dashboard = { withContext(NonCancellable) { oldReply.await() } } }
        val controller = FeedController(backgroundScope, api, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        events.emit(StatusMutation.Deleted(session.revision, 9)); runCurrent()
        oldReply.complete(Result.success(response(status(9)))); runCurrent()
        assertTrue(controller.uiState.value.statuses.isEmpty())
        assertFalse(controller.uiState.value.isLoading)
    }

    @Test fun anIncompleteCommittedUpdateRemovesTheOldCardAndVerifiesItsNextSnapshot() = runTest {
        val events = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)
        val api = Gateway()
        val controller = FeedController(backgroundScope, api, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        val oldReply = CompletableDeferred<Result<StatusListResponse>>()
        api.dashboard = { withContext(NonCancellable) { oldReply.await() } }
        controller.refresh(); runCurrent()
        val verifiedReply = CompletableDeferred<Result<StatusListResponse>>()
        api.dashboard = { verifiedReply.await() }
        events.emit(StatusMutation.Invalidated(session.revision, 1)); runCurrent()
        assertTrue(controller.uiState.value.statuses.isEmpty())
        assertTrue(controller.uiState.value.isRefreshing)
        oldReply.complete(Result.success(response(status(1, "stale")))); runCurrent()
        assertTrue(controller.uiState.value.statuses.isEmpty())
        verifiedReply.complete(Result.success(response(status(1, "verified update")))); runCurrent()
        assertEquals("verified update", controller.uiState.value.statuses.single().body)
    }

    private class Gateway : FeedGateway {
        var dashboard: suspend (Int) -> Result<StatusListResponse> = { Result.success(response(status(1))) }
        var like: suspend (Int) -> Result<Unit> = { Result.success(Unit) }
        override suspend fun getDashboard(page: Int) = dashboard(page)
        override suspend fun getGlobalFeed(page: Int) = dashboard(page)
        override suspend fun likeStatus(id: Int) = like(id)
        override suspend fun unlikeStatus(id: Int) = like(id)
    }

    companion object {
        private fun status(id: Int, body: String = "original"): Status = Gson().fromJson(
            "{\"id\":$id,\"body\":\"$body\",\"liked\":false,\"likes\":0,\"isLikable\":true}", Status::class.java)
        private fun response(vararg statuses: Status) = StatusListResponse(statuses.toList(), null, null)
    }
}
