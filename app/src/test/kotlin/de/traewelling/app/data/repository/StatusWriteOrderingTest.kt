package de.traewelling.app.data.repository

import com.google.gson.Gson
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.StatusDao
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.SingleStatusResponse
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StatusListResponse
import de.traewelling.app.data.model.UpdateStatusRequest
import de.traewelling.app.util.AuthSession
import de.traewelling.app.viewmodel.FeedController
import de.traewelling.app.viewmodel.FeedGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatusWriteOrderingTest {
    @Test fun anotherRepositoryCannotPublishANewerPutBeforeTheOlderPutReply() = runTest {
        val fixture = Fixture()
        val repository = fixture.repository()
        val otherRepository = fixture.repository()
        val feed = FeedController(backgroundScope, gateway(repository), { fixture.session }, fixture.store.events)
        runCurrent(); feed.loadFeed(); runCurrent()
        val oldReply = CompletableDeferred<Unit>()
        fixture.beforePutReply = { request -> if (request.arrival != null) oldReply.await() }

        val correction = async { repository.updateStatus(42, UpdateStatusRequest(arrival = "2026-10-06T16:37:00Z")) }
        runCurrent()
        val editor = async { otherRepository.updateStatus(42, UpdateStatusRequest(body = "new text")) }
        runCurrent()
        assertEquals(listOf("manual time"), fixture.commits)
        assertFalse(editor.isCompleted)
        assertEquals("original", feed.uiState.value.statuses.single().body)

        oldReply.complete(Unit); runCurrent()
        assertEquals("original", correction.await().getOrThrow().body)
        assertEquals("new text", editor.await().getOrThrow().body)
        assertEquals(listOf("manual time", "new text"), fixture.commits)
        assertEquals("new text", fixture.serverStatus.body)
        assertEquals("2026-10-06T16:37:00Z", fixture.serverStatus.checkin?.manualArrival)
        assertEquals("new text", feed.uiState.value.statuses.single().body)
        assertTrue(fixture.dao.rows.isEmpty())
    }

    @Test fun cancellingADispatchedPutDoesNotLetTheNextWriteOvertakeItsReply() = runTest {
        val fixture = Fixture()
        val heldReply = CompletableDeferred<Unit>()
        fixture.beforePutReply = { request -> if (request.arrival != null) heldReply.await() }
        val first = async { fixture.repository().updateStatus(42, UpdateStatusRequest(arrival = "2026-10-06T16:37:00Z")) }
        runCurrent()
        val second = async { fixture.repository().updateStatus(42, UpdateStatusRequest(body = "next")) }
        first.cancel(); runCurrent()
        assertEquals(listOf("manual time"), fixture.commits)
        assertFalse(second.isCompleted)
        heldReply.complete(Unit); runCurrent()
        first.join()
        assertTrue(first.isCancelled)
        assertEquals("next", second.await().getOrThrow().body)
        assertEquals(listOf("manual time", "next"), fixture.commits)
    }

    @Test fun cancellingAQueuedPutNeverDispatchesIt() = runTest {
        val fixture = Fixture()
        val heldReply = CompletableDeferred<Unit>()
        fixture.beforePutReply = { request -> if (request.arrival != null) heldReply.await() }
        val first = async { fixture.repository().updateStatus(42, UpdateStatusRequest(arrival = "2026-10-06T16:37:00Z")) }
        runCurrent()
        val queued = async { fixture.repository().updateStatus(42, UpdateStatusRequest(body = "cancelled")) }
        runCurrent(); queued.cancel(); runCurrent()
        assertTrue(queued.isCancelled)
        heldReply.complete(Unit); runCurrent()
        first.await().getOrThrow()
        assertEquals(listOf("manual time"), fixture.commits)
    }

    @Test fun queuedPutRevalidatesItsCapturedSessionBeforeDispatch() = runTest {
        val fixture = Fixture()
        val heldReply = CompletableDeferred<Unit>()
        fixture.beforePutReply = { request -> if (request.arrival != null) heldReply.await() }
        val events = mutableListOf<StatusMutation>()
        backgroundScope.launch { fixture.store.events.collect { events += it } }
        val first = async { fixture.repository().updateStatus(42, UpdateStatusRequest(arrival = "2026-10-06T16:37:00Z")) }
        runCurrent()
        val queued = async { fixture.repository().updateStatus(42, UpdateStatusRequest(body = "old login")) }
        runCurrent()
        fixture.session = fixture.session.copy(revision = "replacement-login")
        heldReply.complete(Unit); runCurrent()
        first.join(); queued.join()
        assertTrue(first.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(listOf("manual time"), fixture.commits)
        assertTrue(events.isEmpty())
    }

    @Test fun deleteWaitsForThePriorPutAndItsCardCannotBeResurrectedByThatPut() = runTest {
        val fixture = Fixture()
        val repository = fixture.repository()
        val feed = FeedController(backgroundScope, gateway(repository), { fixture.session }, fixture.store.events)
        runCurrent(); feed.loadFeed(); runCurrent()
        val heldReply = CompletableDeferred<Unit>()
        fixture.beforePutReply = { heldReply.await() }
        val edit = async { repository.updateStatus(42, UpdateStatusRequest(body = "edited")) }
        runCurrent()
        val delete = async { fixture.repository().deleteStatus(42) }
        runCurrent()
        assertEquals(0, fixture.deletes)
        heldReply.complete(Unit); runCurrent()
        edit.await().getOrThrow(); delete.await().getOrThrow()
        assertEquals(1, fixture.deletes)
        assertTrue(feed.uiState.value.statuses.isEmpty())
    }

    @Test fun committedLikeRejectsOldFeedRepliesAndBlocksTheirOfflineSnapshot() = runTest {
        val fixture = Fixture()
        val repository = fixture.repository()
        repository.getDashboard().getOrThrow()
        val stale = fixture.serverStatus
        val heldGet = CompletableDeferred<Response<StatusListResponse>>()
        fixture.feedResponse = { heldGet.await() }
        val oldGet = async { fixture.repository().getDashboard() }
        runCurrent()
        val events = mutableListOf<StatusMutation>()
        backgroundScope.launch { fixture.store.events.collect { events += it } }; runCurrent()
        repository.likeStatus(42).getOrThrow(); runCurrent()
        assertEquals(listOf(StatusMutation.LikeChanged(fixture.session.revision, 42, true)), events)
        assertTrue(fixture.dao.rows.isEmpty())
        heldGet.complete(Response.success(StatusListResponse(listOf(stale), null, null))); runCurrent()
        oldGet.join()
        assertTrue(oldGet.isCancelled)
        fixture.feedResponse = { throw IOException("offline") }
        assertTrue(repository.getDashboard().isFailure)
        fixture.feedResponse = { Response.success(StatusListResponse(listOf(fixture.serverStatus), null, null)) }
        assertEquals(true, repository.getDashboard().getOrThrow().data?.single()?.liked)
    }

    @Test fun onlyADispatchedConfirmedDeleteRunsCleanupWhenItsCallerIsCancelled() = runTest {
        val fixture = Fixture()
        val capturedSession = fixture.session
        val heldReply = CompletableDeferred<Unit>()
        fixture.beforeDeleteReply = { heldReply.await() }
        val callbacks = mutableListOf<AuthSession>()
        val first = async {
            fixture.repository().deleteStatus(42, capturedSession) { callbacks += it }
        }
        runCurrent()
        val queued = async {
            fixture.repository().deleteStatus(42, capturedSession) { error("Queued cleanup must not run") }
        }
        runCurrent()
        queued.cancel(); first.cancel(); runCurrent()
        assertEquals(1, fixture.deletes)
        assertTrue(callbacks.isEmpty())
        heldReply.complete(Unit); runCurrent()
        first.join(); queued.join()
        assertTrue(first.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(listOf(capturedSession), callbacks)
        assertEquals(1, fixture.deletes)
    }

    @Test fun failedLikeKeepsTheKnownOfflineFeedAndPublishesNoSuccess() = runTest {
        val fixture = Fixture()
        val repository = fixture.repository()
        repository.getDashboard().getOrThrow()
        val events = mutableListOf<StatusMutation>()
        backgroundScope.launch { fixture.store.events.collect { events += it } }; runCurrent()
        fixture.failLike = true
        assertTrue(repository.likeStatus(42).isFailure)
        runCurrent()
        assertTrue(events.isEmpty())
        fixture.feedResponse = { throw IOException("offline") }
        assertEquals(false, repository.getDashboard().getOrThrow().data?.single()?.liked)
    }

    @Test fun aBodylessAcceptedPutReleasesItsWriteSlotAfterInvalidation() = runTest {
        val fixture = Fixture()
        val heldReply = CompletableDeferred<Unit>()
        fixture.beforePutReply = { request -> if (request.arrival != null) heldReply.await() }
        fixture.bodylessTimeCorrection = true
        val events = mutableListOf<StatusMutation>()
        backgroundScope.launch { fixture.store.events.collect { events += it } }
        val first = async { fixture.repository().updateStatus(42, UpdateStatusRequest(arrival = "2026-10-06T16:37:00Z")) }
        runCurrent()
        val second = async { fixture.repository().updateStatus(42, UpdateStatusRequest(body = "verified")) }
        runCurrent()
        heldReply.complete(Unit); runCurrent()
        assertTrue(first.await().isFailure)
        assertEquals("verified", second.await().getOrThrow().body)
        assertTrue(events.first() is StatusMutation.Invalidated)
        assertEquals("verified", (events.last() as StatusMutation.Updated).status.body)
    }

    private class Fixture {
        var session = AuthSession("https://example.test", "synthetic-test-token", "test-login")
        val store = StatusMutationStore()
        val dao = Dao()
        var serverStatus: Status = Gson().fromJson(
            """{"id":42,"body":"original","liked":false,"likes":0,"isLikable":true,"checkin":{"manualArrival":null}}""",
            Status::class.java
        )
        val commits = mutableListOf<String>()
        var deletes = 0
        var failLike = false
        var bodylessTimeCorrection = false
        var beforePutReply: suspend (UpdateStatusRequest) -> Unit = {}
        var beforeDeleteReply: suspend () -> Unit = {}
        var feedResponse: suspend () -> Response<StatusListResponse> = {
            Response.success(StatusListResponse(listOf(serverStatus), null, null))
        }
        private val unusedApi = Proxy.newProxyInstance(
            TraewellingApiService::class.java.classLoader, arrayOf(TraewellingApiService::class.java)
        ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as TraewellingApiService
        private val api = object : TraewellingApiService by unusedApi {
            override suspend fun getDashboard(page: Int) = feedResponse()
            override suspend fun getGlobalFeed(page: Int) = feedResponse()
            override suspend fun updateStatus(id: Int, request: UpdateStatusRequest): Response<SingleStatusResponse> {
                assertEquals(42, id)
                serverStatus = if (request.arrival != null)
                    serverStatus.copy(checkin = serverStatus.checkin!!.copy(manualArrival = request.arrival))
                else serverStatus.copy(body = request.body)
                commits += request.body ?: "manual time"
                val committedSnapshot = serverStatus
                beforePutReply(request)
                return if (bodylessTimeCorrection && request.arrival != null)
                    Response.success<SingleStatusResponse>(204, null)
                else Response.success(SingleStatusResponse(committedSnapshot))
            }
            override suspend fun deleteStatus(id: Int): Response<Unit> {
                assertEquals(42, id); ++deletes
                beforeDeleteReply()
                return Response.success(Unit)
            }
            override suspend fun likeStatus(id: Int): Response<Unit> {
                assertEquals(42, id)
                if (failLike) return Response.error(500, "{}".toResponseBody())
                serverStatus = serverStatus.copy(liked = true, likes = 1)
                return Response.success(Unit)
            }
            override suspend fun unlikeStatus(id: Int): Response<Unit> {
                assertEquals(42, id)
                serverStatus = serverStatus.copy(liked = false, likes = 0)
                return Response.success(Unit)
            }
        }
        fun repository() = TraewellingRepository(dao, { session }, { api }, store)
    }

    private class Dao : StatusDao {
        val rows = linkedMapOf<Pair<Int, String>, StatusEntity>()
        override suspend fun getStatuses(type: String) = rows.values.filter { it.type == type }.sortedBy { it.position }
        override suspend fun insertStatuses(statuses: List<StatusEntity>) { statuses.forEach { rows[it.id to it.type] = it } }
        override suspend fun clearStatuses(type: String) { rows.keys.removeAll { it.second == type } }
        override suspend fun replaceStatuses(type: String, statuses: List<StatusEntity>) { clearStatuses(type); insertStatuses(statuses) }
    }

    private fun gateway(repository: TraewellingRepository) = object : FeedGateway {
        override suspend fun getDashboard(page: Int) = repository.getDashboard(page)
        override suspend fun getGlobalFeed(page: Int) = repository.getGlobalFeed(page)
        override suspend fun likeStatus(id: Int) = repository.likeStatus(id)
        override suspend fun unlikeStatus(id: Int) = repository.unlikeStatus(id)
    }
}
