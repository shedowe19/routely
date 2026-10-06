package de.traewelling.app.data.repository

import com.google.gson.Gson
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.StatusDao
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.*
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class StatusMutationRepositoryTest {
    private val gson = Gson()
    private val alice = AuthSession("https://example.test", "test-alice-token", "alice-revision")
    private val bob = AuthSession("https://example.test", "test-bob-token", "bob-revision")
    private fun status(body: String = "old", id: Int = 42): Status =
        gson.fromJson("{\"id\":$id,\"body\":\"$body\"}", Status::class.java)
    private fun feed(body: String = "old") = Response.success(StatusListResponse(listOf(status(body)), null, null))
    private val createRequest = CheckInRequest("trip", "ICE", 1, 2,
        "2026-10-06T18:00:00Z", "2026-10-06T18:30:00Z")

    @Test fun deleteInvalidatesBothCurrentPartitionsAndKeepsAnotherAccountsOfflineFeed() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        fixture.repo.getGlobalFeed().getOrThrow()
        fixture.session = bob
        fixture.feedBody = "bob feed"
        fixture.repo.getDashboard().getOrThrow()
        fixture.repo.getGlobalFeed().getOrThrow()
        fixture.session = alice
        val event = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.events.first() }
        fixture.repo.deleteStatus(42).getOrThrow()
        assertEquals(StatusMutation.Deleted(alice.revision, 42), withTimeout(1_000) { event.await() })
        assertEquals(2, fixture.dao.rows.size)
        assertTrue(fixture.dao.rows.values.all { it.statusJson.contains("bob feed") })
        fixture.offline = true
        assertTrue(fixture.repo.getDashboard().isFailure)
        fixture.session = bob
        assertEquals("bob feed", fixture.repo.getDashboard().getOrThrow().data?.single()?.body)
    }

    @Test fun verifiedUpdatePublishesTheReturnedStatusAndInvalidatesOfflineSnapshots() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        val event = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.events.first() }
        val updated = fixture.repo.updateStatus(42, UpdateStatusRequest(body = "updated")).getOrThrow()
        assertEquals("updated", updated.body)
        assertEquals(StatusMutation.Updated(alice.revision, updated), withTimeout(1_000) { event.await() })
        assertTrue(fixture.dao.rows.isEmpty())
        fixture.offline = true
        assertTrue(fixture.repo.getDashboard().isFailure)
    }

    @Test fun failedDeleteDoesNotPublishOrInvalidateAKnownFeed() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        fixture.deleteResponse = { Response.error(403, "{}".toResponseBody()) }
        val events = mutableListOf<StatusMutation>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { fixture.store.events.collect { events += it } }
        assertTrue(fixture.repo.deleteStatus(42).isFailure)
        yield()
        assertTrue(events.isEmpty())
        fixture.offline = true
        assertEquals("old", fixture.repo.getDashboard().getOrThrow().data?.single()?.body)
        collector.cancelAndJoin()
    }

    @Test fun lateCommittedDeleteAfterSameCredentialsReloginCannotResurrectTheOldCache() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<Unit>>()
        fixture.deleteResponse = { entered.complete(Unit); reply.await() }
        val events = mutableListOf<StatusMutation>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { fixture.store.events.collect { events += it } }
        val deletion = async { fixture.repo.deleteStatus(42) }
        entered.await()
        fixture.session = alice.copy(revision = "new-alice-revision")
        reply.complete(Response.success(Unit))
        deletion.join()
        assertTrue(deletion.isCancelled)
        assertTrue(fixture.dao.rows.isEmpty())
        assertTrue(events.isEmpty())
        fixture.offline = true
        assertTrue(fixture.repo.getDashboard().isFailure)
        collector.cancelAndJoin()
    }

    @Test fun lateCommittedOldAccountMutationDoesNotClearTheNewAccountsPartition() = runBlocking {
        val fixture = Fixture()
        fixture.session = bob
        fixture.feedBody = "bob feed"
        fixture.repo.getDashboard().getOrThrow()
        fixture.session = alice
        fixture.feedBody = "alice feed"
        fixture.repo.getDashboard().getOrThrow()
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<Unit>>()
        fixture.deleteResponse = { entered.complete(Unit); reply.await() }
        val deletion = async { fixture.repo.deleteStatus(42) }
        entered.await()
        fixture.session = bob
        reply.complete(Response.success(Unit))
        deletion.join()
        assertTrue(deletion.isCancelled)
        assertEquals(1, fixture.dao.rows.size)
        fixture.offline = true
        assertEquals("bob feed", fixture.repo.getDashboard().getOrThrow().data?.single()?.body)
    }

    @Test fun aFeedReplyFromAnotherRepositoryStartedBeforeDeletionCannotRefillTheInvalidatedCache() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        val otherRepository = fixture.newRepository()
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<StatusListResponse>>()
        fixture.feedResponse = { entered.complete(Unit); reply.await() }
        val loading = async { otherRepository.getDashboard() }
        entered.await()
        fixture.repo.deleteStatus(42).getOrThrow()
        reply.complete(feed("deleted status returned late"))
        loading.join()
        assertTrue(loading.isCancelled)
        assertTrue(fixture.dao.rows.isEmpty())
    }

    @Test fun storageFailureAfterCommitStillPublishesAndBlocksOldCacheUntilFreshNetworkData() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        fixture.dao.failClear = true
        val event = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.events.first() }
        assertTrue(fixture.repo.deleteStatus(42).isSuccess)
        assertTrue(withTimeout(1_000) { event.await() } is StatusMutation.Deleted)
        assertEquals(1, fixture.dao.rows.size)
        fixture.offline = true
        assertTrue(fixture.repo.getDashboard().isFailure)
        fixture.offline = false
        fixture.dao.failClear = false
        fixture.feedBody = "fresh verified feed"
        fixture.repo.getDashboard().getOrThrow()
        fixture.offline = true
        assertEquals("fresh verified feed", fixture.repo.getDashboard().getOrThrow().data?.single()?.body)
    }

    @Test fun cancellationAfterCommitCannotInterruptCacheCleanupAndMutationPublication() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.dao.beforeClear = { entered.complete(Unit); release.await() }
        val event = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.events.first() }
        val deletion = launch { fixture.repo.deleteStatus(42) }
        entered.await()
        deletion.cancel()
        release.complete(Unit)
        deletion.join()
        assertTrue(fixture.dao.rows.isEmpty())
        assertTrue(withTimeout(1_000) { event.await() } is StatusMutation.Deleted)
    }

    @Test fun successfulPutWithoutTrustworthyStatusPublishesInvalidationInsteadOfFakeUpdate() = runBlocking {
        for (data in listOf(null, status(id = 99))) {
            val fixture = Fixture()
            fixture.repo.getDashboard().getOrThrow()
            fixture.updateResponse = { _, _ -> Response.success(SingleStatusResponse(data)) }
            val event = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.events.first() }
            assertTrue(fixture.repo.updateStatus(42, UpdateStatusRequest(body = "new")).isFailure)
            assertEquals(StatusMutation.Invalidated(alice.revision, 42), withTimeout(1_000) { event.await() })
            assertTrue(fixture.dao.rows.isEmpty())
        }
    }

    @Test fun correctionForAnOldExpectedSessionNeverCallsTheNewAccountsApi() = runBlocking {
        val fixture = Fixture()
        fixture.session = bob
        val correction = async { fixture.repo.updateStatus(42, UpdateStatusRequest(arrival = "2026-10-06T18:35:00Z"), alice) }
        correction.join()
        assertTrue(correction.isCancelled)
        assertEquals(0, fixture.puts)
    }

    @Test fun incompleteAcceptedPostHasANonRetryableTypedResult() = runBlocking {
        val fixture = Fixture()
        val result = fixture.repo.checkIn(createRequest, alice)
        assertTrue(result.exceptionOrNull() is CheckInAcceptedException)
        assertEquals(1, fixture.posts)
    }

    @Test fun cacheEpochAndInvalidationLedgersAreBoundedWithAConservativeOverflowFallback() {
        val store = StatusMutationStore()
        repeat(65) { index -> store.invalidate("account-$index", listOf("partition-$index")) }
        assertFalse(store.canReadCache("unlisted-old-partition"))
        val before = store.revisionFor("account-0")
        store.invalidate("later-account", listOf("later-partition"))
        assertTrue(store.revisionFor("account-0") > before)
    }

    private inner class Fixture {
        val dao = Dao()
        val store = StatusMutationStore()
        var session = alice
        var feedBody = "old"
        var offline = false
        var posts = 0
        var puts = 0
        var feedResponse: suspend () -> Response<StatusListResponse> = {
            if (offline) throw IOException("offline")
            feed(feedBody)
        }
        var deleteResponse: suspend () -> Response<Unit> = { Response.success(Unit) }
        var updateResponse: suspend (Int, UpdateStatusRequest) -> Response<SingleStatusResponse> = { id, request ->
            Response.success(SingleStatusResponse(status(request.body ?: "updated", id)))
        }
        private val api = object : TraewellingApiService by unusedService() {
            override suspend fun getDashboard(page: Int) = feedResponse()
            override suspend fun getGlobalFeed(page: Int) = feedResponse()
            override suspend fun deleteStatus(id: Int) = deleteResponse()
            override suspend fun updateStatus(id: Int, request: UpdateStatusRequest): Response<SingleStatusResponse> {
                puts++
                return updateResponse(id, request)
            }
            override suspend fun checkIn(request: CheckInRequest): Response<CheckInResponse> {
                posts++
                return Response.success(201, CheckInResponse(null))
            }
        }
        fun newRepository() = TraewellingRepository(dao, { session }, { api }, store)
        val repo = newRepository()
    }

    private class Dao : StatusDao {
        val rows = linkedMapOf<Pair<Int, String>, StatusEntity>()
        var failClear = false
        var beforeClear: suspend () -> Unit = {}
        override suspend fun getStatuses(type: String) = rows.values.filter { it.type == type }.sortedBy { it.position }
        override suspend fun insertStatuses(statuses: List<StatusEntity>) {
            statuses.forEach { rows[it.id to it.type] = it }
        }
        override suspend fun clearStatuses(type: String) {
            beforeClear()
            if (failClear) throw IOException("cache storage failed")
            rows.keys.removeAll { it.second == type }
        }
        override suspend fun replaceStatuses(type: String, statuses: List<StatusEntity>) {
            clearStatuses(type)
            insertStatuses(statuses)
        }
    }
}
