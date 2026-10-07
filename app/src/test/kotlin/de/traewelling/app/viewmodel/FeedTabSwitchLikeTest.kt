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

/** A previous tab's write must preserve the current tab's outstanding read owner. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedTabSwitchLikeTest {
    private val session = AuthSession("https://example.test", "test-only", "current-session")

    @Test fun eventBeforeLocalLikeCompletionRestartsTheNewTabsInitialLoad() = runTest {
        val events = MutableSharedFlow<StatusMutation>()
        val gateway = Gateway().apply {
            like = {
                likeReply.await()
                events.emit(StatusMutation.LikeChanged(session.revision, 42, true))
                Result.success(Unit)
            }
        }
        val controller = FeedController(backgroundScope, gateway, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(42); runCurrent()
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        assertEquals(listOf(1), gateway.globalReads.map { it.first })

        gateway.likeReply.complete(Unit); runCurrent()
        assertTrue(controller.uiState.value.isRefreshing)
        assertTrue(gateway.globalReads.size >= 2)
        gateway.completeLatestGlobal(response(99)); runCurrent()
        gateway.completeOldGlobals(response(42)); runCurrent()
        assertEquals(listOf(99), controller.uiState.value.statuses.map { it.id })
        assertEquals(FeedType.GLOBAL, controller.uiState.value.feedType)
        assertFalse(controller.uiState.value.isLoading)
        assertFalse(controller.uiState.value.isRefreshing)
        assertFalse(controller.uiState.value.hasMore)
    }

    @Test fun eventAfterLocalLikeCompletionAlsoKeepsTheNewTabsInitialLoad() = runTest {
        val events = MutableSharedFlow<StatusMutation>()
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(42); runCurrent()
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        gateway.likeReply.complete(Unit); runCurrent()
        events.emit(StatusMutation.LikeChanged(session.revision, 42, true)); runCurrent()
        assertTrue(controller.uiState.value.isRefreshing)
        gateway.completeLatestGlobal(response(99)); runCurrent()
        gateway.completeOldGlobals(response(42)); runCurrent()
        assertEquals(listOf(99), controller.uiState.value.statuses.map { it.id })
    }

    @Test fun aFailedPreviousTabLikeDoesNotCancelTheNewTabsReadOrInsertItsCard() = runTest {
        val gateway = Gateway().apply {
            like = { likeReply.await(); Result.failure(IllegalStateException("write rejected")) }
        }
        val controller = FeedController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(42); runCurrent()
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        gateway.likeReply.complete(Unit); runCurrent()
        assertEquals(1, gateway.globalReads.size)
        gateway.completeLatestGlobal(response(99)); runCurrent()
        assertEquals(listOf(99), controller.uiState.value.statuses.map { it.id })
        assertFalse(controller.uiState.value.isRefreshing)
    }

    @Test fun anInterruptedPaginationReadResumesItsPageAndKeepsLoadedGlobalCards() = runTest {
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, MutableSharedFlow())
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(42); runCurrent()
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        gateway.completeLatestGlobal(response(99, hasMore = true)); runCurrent()
        controller.loadMore(); runCurrent()
        assertEquals(listOf(1, 2), gateway.globalReads.map { it.first })

        gateway.likeReply.complete(Unit); runCurrent()
        assertEquals(listOf(1, 2, 2), gateway.globalReads.map { it.first })
        assertEquals(listOf(99), controller.uiState.value.statuses.map { it.id })
        assertTrue(controller.uiState.value.isLoading)
        gateway.completeLatestGlobal(response(99, 100)); runCurrent()
        gateway.completeOldGlobals(response(42)); runCurrent()
        assertEquals(listOf(99, 100), controller.uiState.value.statuses.map { it.id })
        assertEquals(3, controller.uiState.value.currentPage)
        assertFalse(controller.uiState.value.hasMore)
    }

    @Test fun anOldSessionsLikeCompletionCannotCancelTheNewSessionsInitialRead() = runTest {
        val gateway = Gateway()
        val events = MutableSharedFlow<StatusMutation>()
        var currentSession = session
        val controller = FeedController(backgroundScope, gateway, { currentSession }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(42); runCurrent()
        currentSession = session.copy(accessToken = "other-test-only", revision = "new-session")
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        gateway.likeReply.complete(Unit); runCurrent()
        events.emit(StatusMutation.LikeChanged(session.revision, 42, true)); runCurrent()
        assertEquals(1, gateway.globalReads.size)
        gateway.completeLatestGlobal(response(99)); runCurrent()
        assertEquals(listOf(99), controller.uiState.value.statuses.map { it.id })
    }

    @Test fun aQueuedFullStatusSnapshotAfterTheLikeEventCannotEraseAConfirmedIntent() = runTest {
        val events = MutableSharedFlow<StatusMutation>()
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.likeStatus(42); runCurrent()
        gateway.likeReply.complete(Unit); runCurrent()
        events.emit(StatusMutation.LikeChanged(session.revision, 42, true)); runCurrent()
        events.emit(StatusMutation.Updated(session.revision, status(42))); runCurrent()
        assertEquals(true, controller.uiState.value.statuses.single().liked)
        assertEquals(1, controller.uiState.value.statuses.single().likes)
    }

    @Test fun anUnloadedUpdatedStatusDoesNotAbandonTheNewTabsInitialRead() = runTest {
        val events = MutableSharedFlow<StatusMutation>()
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        events.emit(StatusMutation.Updated(session.revision, status(42))); runCurrent()
        assertEquals(listOf(1, 1), gateway.globalReads.map { it.first })
        assertTrue(controller.uiState.value.statuses.isEmpty())
        assertTrue(controller.uiState.value.isRefreshing)
        gateway.completeLatestGlobal(response(99)); runCurrent()
        gateway.completeOldGlobals(response(42)); runCurrent()
        assertEquals(listOf(99), controller.uiState.value.statuses.map { it.id })
    }

    @Test fun anUnloadedDeletedStatusDoesNotAbandonTheNewTabsInitialRead() = runTest {
        val events = MutableSharedFlow<StatusMutation>()
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        events.emit(StatusMutation.Deleted(session.revision, 42)); runCurrent()
        assertEquals(listOf(1, 1), gateway.globalReads.map { it.first })
        gateway.completeLatestGlobal(response(99)); runCurrent()
        gateway.completeOldGlobals(response(42)); runCurrent()
        assertEquals(listOf(99), controller.uiState.value.statuses.map { it.id })
        assertFalse(controller.uiState.value.isRefreshing)
    }

    @Test fun aDeletionDuringPaginationRetainsItsPageWithoutResurrectingTheDeletedCard() = runTest {
        val events = MutableSharedFlow<StatusMutation>()
        val gateway = Gateway()
        val controller = FeedController(backgroundScope, gateway, { session }, events)
        runCurrent(); controller.loadFeed(); runCurrent()
        controller.switchFeedType(FeedType.GLOBAL); runCurrent()
        gateway.completeLatestGlobal(response(99, hasMore = true)); runCurrent()
        controller.loadMore(); runCurrent()
        events.emit(StatusMutation.Deleted(session.revision, 99)); runCurrent()
        assertEquals(listOf(1, 2, 2), gateway.globalReads.map { it.first })
        assertTrue(controller.uiState.value.statuses.isEmpty())
        gateway.completeLatestGlobal(response(100)); runCurrent()
        gateway.completeOldGlobals(response(99)); runCurrent()
        assertEquals(listOf(100), controller.uiState.value.statuses.map { it.id })
    }

    private class Gateway : FeedGateway {
        val likeReply = CompletableDeferred<Unit>()
        val globalReads = mutableListOf<Pair<Int, CompletableDeferred<Result<StatusListResponse>>>>()
        var like: suspend () -> Result<Unit> = { likeReply.await(); Result.success(Unit) }
        override suspend fun getDashboard(page: Int) = Result.success(response(42))
        override suspend fun getGlobalFeed(page: Int): Result<StatusListResponse> {
            val reply = CompletableDeferred<Result<StatusListResponse>>()
            globalReads += page to reply
            return withContext(NonCancellable) { reply.await() }
        }
        override suspend fun likeStatus(id: Int) = like()
        override suspend fun unlikeStatus(id: Int) = error("Unexpected unlike")
        fun completeLatestGlobal(response: StatusListResponse) {
            globalReads.last().second.complete(Result.success(response))
        }
        fun completeOldGlobals(response: StatusListResponse) {
            globalReads.dropLast(1).forEach { it.second.complete(Result.success(response)) }
        }
    }

    companion object {
        private fun status(id: Int): Status = Gson().fromJson(
            "{\"id\":$id,\"body\":\"status$id\",\"liked\":false,\"likes\":0,\"isLikable\":true}", Status::class.java)
        private fun response(vararg ids: Int, hasMore: Boolean = false): StatusListResponse {
            val response = Gson().fromJson(
                "{\"links\":{\"next\":${if (hasMore) "\"https://example.test/next\"" else "null"}}}",
                StatusListResponse::class.java)
            return response.copy(data = ids.map(::status))
        }
    }
}
