package de.traewelling.app.data.repository

import com.google.gson.Gson
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.StatusDao
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.SingleStatusResponse
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.UpdateStatusRequest
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class StatusTimeCommitRepositoryTest {
    private val owner = AuthSession("https://example.test", "test-token", "session-one")
    private val request = UpdateStatusRequest(arrival = "2026-11-03T10:30:00Z")
    private fun status(): Status = Gson().fromJson("{\"id\":42}", Status::class.java)

    @Test fun cancellationAfterDispatchStillCompletesTheMatchingArrivalOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<SingleStatusResponse>>()
        var completed = 0
        val repo = repository({ owner }) { entered.complete(Unit); reply.await() }
        val write = async { repo.updateStatus(42, request, owner) { committed ->
            assertEquals(owner, committed); completed++
        } }
        entered.await()
        write.cancel()
        reply.complete(Response.success(SingleStatusResponse(status())))
        write.join()
        assertTrue(write.isCancelled)
        assertEquals(1, completed)
    }

    @Test fun rejectedPutNeverCompletesArrival() = runBlocking {
        var completed = 0
        val repo = repository({ owner }) { Response.error(400, "{}".toResponseBody()) }
        assertTrue(repo.updateStatus(42, request, owner) { completed++ }.isFailure)
        assertEquals(0, completed)
    }

    @Test fun aReplacementSessionCannotBeStoppedByAnOldCommittedResponse() = runBlocking {
        var session = owner
        var completed = 0
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<SingleStatusResponse>>()
        val repo = repository({ session }) { entered.complete(Unit); reply.await() }
        val write = async { repo.updateStatus(42, request, owner) { completed++ } }
        entered.await()
        session = owner.copy(revision = "session-two")
        reply.complete(Response.success(SingleStatusResponse(status())))
        write.join()
        assertTrue(write.isCancelled)
        assertEquals(0, completed)
    }

    @Test fun aLoadedOldSessionCannotDispatchAnIdenticallyNumberedStatusOnANewLogin() = runBlocking {
        var calls = 0
        var completed = 0
        val repo = repository({ owner.copy(revision = "session-two") }) {
            calls++
            Response.success(SingleStatusResponse(status()))
        }
        val write = async { repo.updateStatus(42, request, owner) { completed++ } }
        write.join()
        assertTrue(write.isCancelled)
        assertEquals(0, calls)
        assertEquals(0, completed)
    }

    @Test fun aSuccessfulBodylessPutIsStillCommittedExactlyOnce() = runBlocking {
        var completed = 0
        val repo = repository({ owner }) { Response.success<SingleStatusResponse>(null) }
        val result = repo.updateStatus(42, request, owner) { completed++ }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("angenommen"))
        assertEquals(1, completed)
    }

    private fun repository(session: suspend () -> AuthSession,
        response: suspend () -> Response<SingleStatusResponse>): TraewellingRepository {
        val api = object : TraewellingApiService by unusedService() {
            override suspend fun updateStatus(id: Int, request: UpdateStatusRequest) = response()
        }
        return TraewellingRepository(EmptyDao(), session, { api }, StatusMutationStore())
    }

    private class EmptyDao : StatusDao {
        override suspend fun getStatuses(type: String): List<StatusEntity> = emptyList()
        override suspend fun insertStatuses(statuses: List<StatusEntity>) = Unit
        override suspend fun clearStatuses(type: String) = Unit
    }
}
