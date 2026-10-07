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

    @Test fun detailReadStartedBeforeDestinationMutationCannotReturnItsOldSnapshot() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<SingleStatusResponse>>()
        fixture.statusResponse = { entered.complete(Unit); reply.await() }
        val reading = async { fixture.newRepository().getStatusDetail(42) }
        entered.await()
        fixture.repo.updateStatus(42, UpdateStatusRequest(body = "new destination")).getOrThrow()
        reply.complete(Response.success(SingleStatusResponse(status("old destination"))))
        assertTrue(reading.await().isFailure)
        assertFalse(reading.isCancelled)
    }

    @Test fun detailSnapshotRetainsARevisionForTheSubsequentStopoverWait() = runBlocking {
        val fixture = Fixture()
        val detail = fixture.repo.getStatusDetailSnapshot(42).getOrThrow()
        fixture.repo.updateStatus(42, UpdateStatusRequest(body = "edited while stopovers load")).getOrThrow()
        val current = fixture.repo.getStatusMutationSnapshot(42, alice)
        assertNotEquals(detail.revision, current.revision)
        assertEquals("edited while stopovers load", (current.mutation as StatusMutation.Updated).status.body)
    }

    @Test fun aLikeCannotEraseTheUnconsumedFullDestinationMutation() = runBlocking {
        val fixture = Fixture()
        fixture.repo.updateStatus(42, UpdateStatusRequest(body = "changed destination")).getOrThrow()
        val update = fixture.repo.getStatusMutationSnapshot(42, alice)
        fixture.repo.likeStatus(42).getOrThrow()
        fixture.repo.unlikeStatus(42).getOrThrow()
        assertEquals(update, fixture.repo.getStatusMutationSnapshot(42, alice))
    }

    @Test fun unrelatedLikesCannotPauseAnUneditedActiveTripEvenWithoutALedgerEntry() = runBlocking {
        val fixture = Fixture()
        val before = fixture.repo.getStatusMutationSnapshot(42, alice)
        fixture.repo.likeStatus(99).getOrThrow()
        fixture.repo.unlikeStatus(42).getOrThrow()
        assertEquals(before, fixture.repo.getStatusMutationSnapshot(42, alice))
        assertEquals(0L, before.revision)
    }

    @Test fun aVerifiedBaselineIgnoresOtherTripsContentUpdates() = runBlocking {
        val fixture = Fixture()
        val before = fixture.repo.getStatusDetailSnapshot(42).getOrThrow()
        fixture.repo.updateStatus(99, UpdateStatusRequest(body = "other ride")).getOrThrow()
        assertEquals(before.revision, fixture.repo.getStatusMutationSnapshot(42, alice).revision)
        fixture.repo.updateStatus(42, UpdateStatusRequest(body = "active ride")).getOrThrow()
        assertNotEquals(before.revision, fixture.repo.getStatusMutationSnapshot(42, alice).revision)
    }

    @Test fun automaticCompletionWaitsForAnAlreadyDispatchedPutAndRejectsTheOldRevision() = runBlocking {
        val fixture = Fixture()
        val old = fixture.repo.getStatusDetailSnapshot(42).getOrThrow()
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<SingleStatusResponse>>()
        fixture.updateResponse = { _, _ -> entered.complete(Unit); reply.await() }
        val editing = async { fixture.repo.updateStatus(42, UpdateStatusRequest(body = "extended target")) }
        entered.await()
        var cleared = false
        val completion = async { fixture.newRepository().withStatusRevision(42, alice, old.revision) { cleared = true } }
        yield()
        assertFalse(completion.isCompleted)
        reply.complete(Response.success(SingleStatusResponse(status("extended target"))))
        editing.await().getOrThrow()
        assertFalse(completion.await())
        assertFalse(cleared)
    }

    @Test fun destinationClearAndANewerDispatchedEditCannotOvertakeEachOther() = runBlocking {
        val fixture = Fixture()
        val revision = fixture.repo.getStatusDetailSnapshot(42).getOrThrow().revision
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completion = async { fixture.repo.withStatusRevision(42, alice, revision) { entered.complete(Unit); release.await() } }
        entered.await()
        val editing = async { fixture.newRepository().updateStatus(42, UpdateStatusRequest(body = "later edit")) }
        yield()
        assertEquals(0, fixture.puts)
        release.complete(Unit)
        assertTrue(completion.await())
        editing.await().getOrThrow()
        assertEquals(1, fixture.puts)
    }

    @Test fun incompletePutInvalidatesTheOldDetailRevisionUntilANewReadSucceeds() = runBlocking {
        val fixture = Fixture()
        val before = fixture.repo.getStatusDetailSnapshot(42).getOrThrow()
        fixture.updateResponse = { _, _ -> Response.success(SingleStatusResponse(null)) }
        assertTrue(fixture.repo.updateStatus(42, UpdateStatusRequest(body = "accepted without body")).isFailure)
        val committed = fixture.repo.getStatusMutationSnapshot(42, alice)
        assertTrue(committed.mutation is StatusMutation.Invalidated)
        assertNotEquals(before.revision, committed.revision)
        fixture.statusResponse = { Response.success(SingleStatusResponse(status("verified fresh target"))) }
        val verified = fixture.repo.getStatusDetailSnapshot(42).getOrThrow()
        assertEquals(committed.revision, verified.revision)
        assertEquals("verified fresh target", verified.status.body)
    }

    @Test fun contentLedgerEvictionCannotMakeAnOlderTrackingRevisionCurrent() {
        val store = StatusMutationStore()
        store.invalidate("alice", emptyList())
        store.recordContentMutation("alice", StatusMutation.Updated(alice.revision, status("first")))
        val first = store.contentSnapshot("alice", 42)
        repeat(65) { index ->
            store.invalidate("alice", emptyList())
            store.recordContentMutation("alice", StatusMutation.Updated(alice.revision, status(id = 100 + index)))
        }
        val evicted = store.contentSnapshot("alice", 42)
        assertNull(evicted.mutation)
        assertTrue(evicted.revision > first.revision)
        assertEquals(0L, store.contentSnapshot("bob", 42).revision)
    }

    @Test fun committedEditInvalidatesThePersistedOldRouteBeforeReturningOrPublishing() = runBlocking {
        val fixture = Fixture()
        val before = fixture.repo.getStatusDetailSnapshot(42).getOrThrow()
        var persisted: String? = "old-target"
        val invalidationEntered = CompletableDeferred<Unit>()
        val releaseInvalidation = CompletableDeferred<Unit>()
        fixture.trackingInvalidator = { id, owner ->
            assertEquals(42, id); assertEquals(alice, owner)
            invalidationEntered.complete(Unit)
            releaseInvalidation.await()
            persisted = null
        }
        val event = async(start = CoroutineStart.UNDISPATCHED) { fixture.store.events.first() }
        val editing = async { fixture.repo.updateStatus(42, UpdateStatusRequest(body = "new-target")) }
        invalidationEntered.await()
        assertFalse(editing.isCompleted)
        assertFalse(event.isCompleted)
        releaseInvalidation.complete(Unit)
        editing.await().getOrThrow()
        assertNull(persisted)
        assertTrue(event.await() is StatusMutation.Updated)
        assertFalse(fixture.repo.withStatusRevision(42, alice, before.revision) { persisted = "stale-late-write" })
        assertNull(persisted)
    }

    @Test fun anAlreadyStartedCacheWriteFinishesBeforeThePutInvalidatesIt() = runBlocking {
        val fixture = Fixture()
        val revision = fixture.repo.getStatusDetailSnapshot(42).getOrThrow().revision
        var persisted: String? = null
        fixture.trackingInvalidator = { _, _ -> persisted = null }
        val writing = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val save = async { fixture.repo.withStatusRevision(42, alice, revision) {
            writing.complete(Unit); releaseWrite.await(); persisted = "old-target"
        } }
        writing.await()
        val editing = async { fixture.repo.updateStatus(42, UpdateStatusRequest(body = "new-target")) }
        yield()
        assertEquals(0, fixture.puts)
        releaseWrite.complete(Unit)
        assertTrue(save.await())
        editing.await().getOrThrow()
        assertNull(persisted)
    }

    private inner class Fixture {
        val dao = Dao()
        val store = StatusMutationStore()
        var session = alice
        var feedBody = "old"
        var offline = false
        var posts = 0
        var puts = 0
        var trackingInvalidator: suspend (Int, AuthSession) -> Unit = { _, _ -> }
        var feedResponse: suspend () -> Response<StatusListResponse> = {
            if (offline) throw IOException("offline")
            feed(feedBody)
        }
        var deleteResponse: suspend () -> Response<Unit> = { Response.success(Unit) }
        var updateResponse: suspend (Int, UpdateStatusRequest) -> Response<SingleStatusResponse> = { id, request ->
            Response.success(SingleStatusResponse(status(request.body ?: "updated", id)))
        }
        var statusResponse: suspend () -> Response<SingleStatusResponse> = { Response.success(SingleStatusResponse(status())) }
        private val api = object : TraewellingApiService by unusedService() {
            override suspend fun getDashboard(page: Int) = feedResponse()
            override suspend fun getGlobalFeed(page: Int) = feedResponse()
            override suspend fun getStatus(id: Int) = statusResponse()
            override suspend fun deleteStatus(id: Int) = deleteResponse()
            override suspend fun likeStatus(id: Int) = Response.success(Unit)
            override suspend fun unlikeStatus(id: Int) = Response.success(Unit)
            override suspend fun updateStatus(id: Int, request: UpdateStatusRequest): Response<SingleStatusResponse> {
                puts++
                return updateResponse(id, request)
            }
            override suspend fun checkIn(request: CheckInRequest): Response<CheckInResponse> {
                posts++
                return Response.success(201, CheckInResponse(null))
            }
        }
        fun newRepository() = TraewellingRepository(dao, { session }, { api }, store,
            { id, owner -> trackingInvalidator(id, owner) })
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
