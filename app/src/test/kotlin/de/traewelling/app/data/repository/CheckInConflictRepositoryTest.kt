package de.traewelling.app.data.repository

import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.StatusDao
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.CheckInConflictException
import de.traewelling.app.data.model.CheckInRequest
import de.traewelling.app.data.model.CheckInResponse
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class CheckInConflictRepositoryTest {
    private val session = AuthSession("https://example.test", "test-token", "test-session")
    private val request = CheckInRequest("trip", "RE 1", 1, 2,
        "2026-11-03T10:00:00Z", "2026-11-03T11:00:00Z")

    @Test fun conflictUsesAllCurrentStatusesWithoutLegacyMessageFields() = runBlocking {
        var calls = 0
        val api = service {
            calls++
            assertEquals(request, it)
            Response.error(409, """{"data":{"conflicts":[
                {"id":7,"checkin":{"lineName":"RE 1","destination":{"station":{"name":"Teststadt"}}}},
                {"id":8,"checkin":{"lineName":"S 2"}}
            ]}}""".toResponseBody())
        }
        val failure = repository(api).checkIn(request).exceptionOrNull()

        assertTrue(failure is CheckInConflictException)
        assertEquals(listOf(7, 8), (failure as CheckInConflictException).conflicts.map { it.id })
        assertTrue(failure.message.orEmpty().contains("RE 1 nach Teststadt"))
        assertEquals(1, calls)
    }

    @Test fun malformedConflictBodiesProduceAGenericConflictWithoutLeakingRawResponse() = runBlocking {
        for (body in listOf("not json: private server details", "{}", "{\"data\":{\"conflicts\":[]}}")) {
            val api = service { Response.error(409, body.toResponseBody()) }
            val failure = repository(api).checkIn(request).exceptionOrNull()
            assertTrue(failure is CheckInConflictException)
            assertTrue((failure as CheckInConflictException).conflicts.isEmpty())
            assertFalse(failure.message.orEmpty().contains("private server details"))
        }
    }

    @Test fun anotherHttpErrorIsNotMisreportedAsAConflict() = runBlocking {
        val failure = repository(service { Response.error(401, "{}".toResponseBody()) })
            .checkIn(request).exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure is CheckInConflictException)
        assertTrue(failure?.message.orEmpty().contains("401"))
    }

    @Test fun sessionChangeDuringConflictReplyCancelsInsteadOfReturningAnotherAccountsData() = runBlocking {
        var active = session
        val api = service {
            active = session.copy(revision = "new-session")
            Response.error(409, """{"data":{"conflicts":[{"id":7}]}}""".toResponseBody())
        }
        val repo = TraewellingRepository(noCache, { active }, { api })
        val call = async { repo.checkIn(request, session) }
        call.join()
        assertTrue(call.isCancelled)
    }

    private fun repository(api: TraewellingApiService) =
        TraewellingRepository(noCache, { session }, { api })

    private fun service(reply: suspend (CheckInRequest) -> Response<CheckInResponse>) =
        object : TraewellingApiService by unusedService() {
            override suspend fun checkIn(request: CheckInRequest) = reply(request)
        }

    private val noCache = object : StatusDao {
        override suspend fun getStatuses(type: String): List<StatusEntity> = error("Unexpected cache read")
        override suspend fun insertStatuses(statuses: List<StatusEntity>) = error("Unexpected cache write")
        override suspend fun clearStatuses(type: String) = error("Unexpected cache clear")
        override suspend fun replaceStatuses(type: String, statuses: List<StatusEntity>) = error("Unexpected cache replace")
    }
}
