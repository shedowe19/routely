package de.traewelling.app.data.repository

import com.google.gson.Gson
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.StatusDao
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.CheckInRequest
import de.traewelling.app.data.model.CheckInResponse
import de.traewelling.app.data.model.CheckInResult
import de.traewelling.app.data.model.SingleStatusResponse
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StatusListResponse
import de.traewelling.app.data.model.UpdateStatusRequest
import de.traewelling.app.util.AuthSession
import de.traewelling.app.util.PreferenceStorageException
import de.traewelling.app.util.PreferenceStoragePhase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class StorageCommitRecoveryRepositoryTest {
    private val owner = AuthSession("https://example.test", "synthetic-owner-token", "owner-revision")
    private val other = AuthSession("https://example.test", "synthetic-other-token", "other-revision")
    private val request = CheckInRequest("trip", "RE 1", 1, 2,
        "2026-11-03T10:00:00Z", "2026-11-03T11:00:00Z")
    private val correction = UpdateStatusRequest(arrival = "2026-11-03T10:30:00Z")
    private val storageFailure = PreferenceStorageException(PreferenceStoragePhase.IO_ERROR)
    private fun status(body: String = "saved"): Status =
        Gson().fromJson("{\"id\":42,\"body\":\"$body\"}", Status::class.java)

    @Test fun acceptedPostPreservesItsCreatedIdWhenTheFollowingSessionReadFails() = runBlocking {
        val fixture = Fixture()
        val created = CheckInResult(status(), null)
        fixture.postResponse = {
            fixture.storageBroken = true
            Response.success(201, CheckInResponse(created))
        }
        val failure = fixture.repo.checkIn(request, owner).exceptionOrNull() as CheckInAcceptedException
        assertSame(created, failure.created)
        assertSame(storageFailure, failure.cause)
        assertEquals(42, failure.created?.status?.id)
        assertTrue(failure.message.orEmpty().contains("erneuter Check-in ist nicht nötig"))
        assertEquals(1, fixture.posts)
        assertEquals(0, fixture.puts)
    }

    @Test fun acceptedBodylessPostStillCannotBecomeRetryableAfterStorageFailure() = runBlocking {
        val fixture = Fixture()
        fixture.postResponse = {
            fixture.storageBroken = true
            Response.success<CheckInResponse>(201, null)
        }
        val failure = fixture.repo.checkIn(request).exceptionOrNull() as CheckInAcceptedException
        assertNull(failure.created)
        assertSame(storageFailure, failure.cause)
        assertEquals(1, fixture.posts)
    }

    @Test fun rejectedPostIsNeverPromotedToAcceptedWhenLocalStorageAlsoFails() = runBlocking {
        val fixture = Fixture()
        fixture.postResponse = {
            fixture.storageBroken = true
            Response.error(403, "{}".toResponseBody())
        }
        val failure = fixture.repo.checkIn(request).exceptionOrNull()
        assertSame(storageFailure, failure)
        assertFalse(failure is CheckInAcceptedException)
        assertEquals(1, fixture.posts)
    }

    @Test fun acceptedPutRetainsItsResponseButCannotRunUnverifiedSessionCleanup() = runBlocking {
        val fixture = Fixture()
        val updated = status("corrected")
        var cleanups = 0
        val events = mutableListOf<StatusMutation>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { fixture.mutations.events.collect { events += it } }
        fixture.putResponse = {
            fixture.storageBroken = true
            Response.success(SingleStatusResponse(updated))
        }
        val failure = fixture.repo.updateStatus(42, correction, owner) { cleanups++ }
            .exceptionOrNull() as StatusWriteAcceptedException
        yield()
        assertEquals(42, failure.statusId)
        assertSame(updated, failure.status)
        assertFalse(failure.deleted)
        assertSame(storageFailure, failure.cause)
        assertEquals(0, cleanups)
        assertTrue(events.isEmpty())
        assertEquals(1, fixture.puts)
        observer.cancelAndJoin()
    }

    @Test fun acceptedBodylessPutStillHasItsIdAndSuppressesUnverifiedCleanup() = runBlocking {
        val fixture = Fixture()
        var cleanups = 0
        fixture.putResponse = {
            fixture.storageBroken = true
            Response.success<SingleStatusResponse>(null)
        }
        val failure = fixture.repo.updateStatus(42, correction, owner) { cleanups++ }
            .exceptionOrNull() as StatusWriteAcceptedException
        assertEquals(42, failure.statusId)
        assertNull(failure.status)
        assertFalse(failure.deleted)
        assertEquals(0, cleanups)
        assertEquals(1, fixture.puts)
    }

    @Test fun acceptedDeleteCannotBecomeARetryOrClearAnotherSessionsLocalTrip() = runBlocking {
        val fixture = Fixture()
        var cleanups = 0
        fixture.deleteResponse = {
            fixture.session = other
            fixture.storageBroken = true
            Response.success(Unit)
        }
        val failure = fixture.repo.deleteStatus(42, owner) { cleanups++ }
            .exceptionOrNull() as StatusWriteAcceptedException
        assertEquals(42, failure.statusId)
        assertTrue(failure.deleted)
        assertSame(storageFailure, failure.cause)
        assertEquals(0, cleanups)
        assertEquals(other, fixture.session)
        assertEquals(1, fixture.deletes)
    }

    @Test fun rejectedPutAndDeleteNeverClaimACommittedWriteOrInvokeCleanup() = runBlocking {
        val fixture = Fixture()
        var cleanups = 0
        fixture.putResponse = { Response.error(400, "{}".toResponseBody()) }
        fixture.deleteResponse = { Response.error(403, "{}".toResponseBody()) }
        val putFailure = fixture.repo.updateStatus(42, correction, owner) { cleanups++ }.exceptionOrNull()
        val deleteFailure = fixture.repo.deleteStatus(42, owner) { cleanups++ }.exceptionOrNull()
        assertNotNull(putFailure)
        assertNotNull(deleteFailure)
        assertFalse(putFailure is StatusWriteAcceptedException)
        assertFalse(deleteFailure is StatusWriteAcceptedException)
        assertEquals(0, cleanups)
        assertEquals(1, fixture.puts)
        assertEquals(1, fixture.deletes)
    }

    @Test fun freshStorageFailurePreventsEveryMutationBeforeItsHttpDispatch() = runBlocking {
        val fixture = Fixture()
        fixture.storageBroken = true
        assertSame(storageFailure, fixture.repo.checkIn(request).exceptionOrNull())
        assertSame(storageFailure, fixture.repo.updateStatus(42, correction).exceptionOrNull())
        assertSame(storageFailure, fixture.repo.deleteStatus(42).exceptionOrNull())
        assertEquals(0, fixture.posts)
        assertEquals(0, fixture.puts)
        assertEquals(0, fixture.deletes)
        assertEquals(0, fixture.apiCreations)
    }

    @Test fun aStorageFaultAfterFeedResponseCannotFallBackToAnOldPrivateSnapshot() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        fixture.feedBody = "new response"
        fixture.afterFeedResponse = { fixture.failNextSessionRead = true }
        val failure = fixture.repo.getDashboard().exceptionOrNull()
        assertSame(storageFailure, failure)
        assertEquals(0, fixture.dao.cacheReads)
        assertTrue(fixture.dao.rows.values.single().statusJson.contains("old feed"))
    }

    @Test fun acceptedPutBlocksTheOldCacheEvenWhenDiskClearAndSessionVerificationFail() = runBlocking {
        val fixture = Fixture()
        fixture.repo.getDashboard().getOrThrow()
        fixture.dao.failClear = true
        fixture.putResponse = {
            fixture.storageBroken = true
            Response.success(SingleStatusResponse(status("new target")))
        }
        assertTrue(fixture.repo.updateStatus(42, correction).exceptionOrNull() is StatusWriteAcceptedException)
        assertEquals(1, fixture.dao.rows.size)
        fixture.storageBroken = false
        fixture.offline = true
        assertTrue(fixture.repo.getDashboard().isFailure)
        assertEquals(0, fixture.dao.cacheReads)
        fixture.dao.failClear = false
        fixture.offline = false
        fixture.feedBody = "fresh confirmed feed"
        fixture.repo.getDashboard().getOrThrow()
        fixture.offline = true
        assertEquals("fresh confirmed feed", fixture.repo.getDashboard().getOrThrow().data?.single()?.body)
        assertEquals(1, fixture.puts)
    }

    @Test fun unverifiedOldAccountCommitKeepsAnotherAccountsCachedFeedIntact() = runBlocking {
        val fixture = Fixture()
        fixture.session = other
        fixture.feedBody = "other account"
        fixture.repo.getDashboard().getOrThrow()
        fixture.session = owner
        fixture.feedBody = "owner account"
        fixture.repo.getDashboard().getOrThrow()
        fixture.deleteResponse = {
            fixture.session = other
            fixture.storageBroken = true
            Response.success(Unit)
        }
        assertTrue(fixture.repo.deleteStatus(42, owner).exceptionOrNull() is StatusWriteAcceptedException)
        fixture.storageBroken = false
        fixture.offline = true
        assertEquals("other account", fixture.repo.getDashboard().getOrThrow().data?.single()?.body)
        assertEquals(1, fixture.deletes)
    }

    private inner class Fixture {
        val dao = Dao()
        val mutations = StatusMutationStore()
        var session = owner
        var storageBroken = false
        var failNextSessionRead = false
        var apiCreations = 0
        var posts = 0
        var puts = 0
        var deletes = 0
        var offline = false
        var feedBody = "old feed"
        var afterFeedResponse: () -> Unit = {}
        var postResponse: suspend () -> Response<CheckInResponse> = {
            Response.success(201, CheckInResponse(CheckInResult(status(), null)))
        }
        var putResponse: suspend () -> Response<SingleStatusResponse> = { Response.success(SingleStatusResponse(status())) }
        var deleteResponse: suspend () -> Response<Unit> = { Response.success(Unit) }
        private val api = object : TraewellingApiService by unusedService() {
            override suspend fun checkIn(request: CheckInRequest): Response<CheckInResponse> {
                posts++
                return postResponse()
            }
            override suspend fun updateStatus(id: Int, request: UpdateStatusRequest): Response<SingleStatusResponse> {
                puts++
                return putResponse()
            }
            override suspend fun deleteStatus(id: Int): Response<Unit> {
                deletes++
                return deleteResponse()
            }
            override suspend fun getDashboard(page: Int): Response<StatusListResponse> {
                if (offline) throw IOException("synthetic network failure")
                afterFeedResponse()
                return Response.success(StatusListResponse(listOf(status(feedBody)), null, null))
            }
        }
        val repo = TraewellingRepository(dao, {
            if (storageBroken || failNextSessionRead) {
                failNextSessionRead = false
                throw storageFailure
            }
            session
        }, { apiCreations++; api }, mutations)
    }

    private class Dao : StatusDao {
        val rows = linkedMapOf<Pair<Int, String>, StatusEntity>()
        var failClear = false
        var cacheReads = 0
        override suspend fun getStatuses(type: String): List<StatusEntity> {
            cacheReads++
            return rows.values.filter { it.type == type }.sortedBy { it.position }
        }
        override suspend fun insertStatuses(statuses: List<StatusEntity>) {
            statuses.forEach { rows[it.id to it.type] = it }
        }
        override suspend fun clearStatuses(type: String) {
            if (failClear) throw IOException("synthetic cache failure")
            rows.keys.removeAll { it.second == type }
        }
        override suspend fun replaceStatuses(type: String, statuses: List<StatusEntity>) {
            clearStatuses(type)
            insertStatuses(statuses)
        }
    }
}
