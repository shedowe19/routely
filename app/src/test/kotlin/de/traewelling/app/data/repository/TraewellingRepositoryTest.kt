package de.traewelling.app.data.repository

import com.google.gson.Gson
import de.traewelling.app.data.api.TraewellingApiService
import de.traewelling.app.data.local.StatusDao
import de.traewelling.app.data.local.StatusEntity
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StatusListResponse
import de.traewelling.app.data.model.UnreadCountResponse
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class TraewellingRepositoryTest {
    private val gson = Gson()
    private val alice = AuthSession("https://example.test", "alice-token", "alice-revision")

    @Test fun offlineCacheBelongsToItsServerAndCredentials() = runBlocking {
        var session = alice
        var offline = false
        val dao = Dao()
        val repo = repository(dao, { session }) {
            if (offline) throw IOException("offline")
            feed(status(42, "private alice feed"))
        }
        assertTrue(repo.getDashboard().isSuccess)
        offline = true
        assertEquals("private alice feed", repo.getDashboard().getOrThrow().data?.single()?.body)
        session = alice.copy(accessToken = "bob-token", revision = "bob-revision")
        assertTrue(repo.getDashboard().isFailure)
        session = alice.copy(serverUrl = "https://other.test", revision = "other-revision")
        assertTrue(repo.getDashboard().isFailure)
        assertTrue(dao.rows.keys.all { !it.second.contains("alice-token") })
    }

    @Test fun successfulEmptySnapshotRemovesOldPrivateRows() = runBlocking {
        val dao = Dao()
        var response = feed(status(42, "old feed"))
        val repo = repository(dao, { alice }) { response }
        repo.getDashboard().getOrThrow()
        response = feed()
        assertTrue(repo.getDashboard().getOrThrow().data.orEmpty().isEmpty())
        assertTrue(dao.rows.isEmpty())
        assertEquals(2, dao.replacements)
    }

    @Test fun offlineSnapshotPreservesApiOrderInsteadOfSortingByCreationId() = runBlocking {
        val dao = Dao()
        var offline = false
        val repo = repository(dao, { alice }) {
            if (offline) throw IOException("offline")
            feed(status(10, "later departure"), status(99, "earlier departure"))
        }
        assertEquals(listOf(10, 99), repo.getDashboard().getOrThrow().data?.map { it.id })
        offline = true
        assertEquals(listOf(10, 99), repo.getDashboard().getOrThrow().data?.map { it.id })
    }

    @Test fun dashboardAndGlobalSnapshotsDoNotOverwriteSameStatusId() = runBlocking {
        val dao = Dao()
        var offline = false
        val api = object : TraewellingApiService by unusedService() {
            override suspend fun getDashboard(page: Int): Response<StatusListResponse> {
                if (offline) throw IOException("offline")
                return feed(status(42, "dashboard"))
            }
            override suspend fun getGlobalFeed(page: Int): Response<StatusListResponse> {
                if (offline) throw IOException("offline")
                return feed(status(42, "global"))
            }
        }
        val repo = TraewellingRepository(dao, { alice }, { api })
        repo.getDashboard().getOrThrow()
        repo.getGlobalFeed().getOrThrow()
        assertEquals(2, dao.rows.size)
        offline = true
        assertEquals("dashboard", repo.getDashboard().getOrThrow().data?.single()?.body)
        assertEquals("global", repo.getGlobalFeed().getOrThrow().data?.single()?.body)
    }

    @Test fun unauthorizedAndMalformedResponsesNeverFallBackToCache() = runBlocking {
        val dao = Dao()
        var response = feed(status(42, "old feed"))
        val repo = repository(dao, { alice }) { response }
        repo.getDashboard().getOrThrow()
        for (error in listOf(Response.error<StatusListResponse>(401, "{}".toResponseBody()),
            Response.success(StatusListResponse(null, null, null)))) {
            response = error
            assertTrue(repo.getDashboard().isFailure)
        }
        assertEquals(0, dao.reads)
    }

    @Test fun cancellationPropagatesWithoutReadingOfflineCache() = runBlocking {
        val dao = Dao()
        val repo = repository(dao, { alice }) { throw CancellationException("cancelled request") }
        val request = async { repo.getDashboard() }
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(0, dao.reads)
    }

    @Test fun accountChangeDuringNetworkReplyNeverCachesOrReturnsOldFeed() = runBlocking {
        var session = alice
        val dao = Dao()
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Response<StatusListResponse>>()
        val repo = repository(dao, { session }) { entered.complete(Unit); reply.await() }
        val request = async { repo.getDashboard() }
        entered.await()
        session = alice.copy(accessToken = "bob-token", revision = "bob-revision")
        reply.complete(feed(status(42, "old account")))
        request.join()
        assertTrue(request.isCancelled)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun accountChangeWhileDaoSuspendsDiscardsOldOfflineSnapshot() = runBlocking {
        var session = alice
        val dao = Dao()
        var offline = false
        val repo = repository(dao, { session }) {
            if (offline) throw IOException("offline")
            feed(status(42, "old account"))
        }
        repo.getDashboard().getOrThrow()
        offline = true
        dao.beforeRead = { session = alice.copy(accessToken = "bob-token", revision = "bob-revision") }
        val request = async { repo.getDashboard() }
        request.join()
        assertTrue(request.isCancelled)
    }

    @Test fun accountChangeDuringCacheWriteCannotPublishOldAccountResponse() = runBlocking {
        var session = alice
        val dao = Dao()
        dao.afterReplace = { session = alice.copy(accessToken = "bob-token", revision = "bob-revision") }
        val repo = repository(dao, { session }) { feed(status(42, "old account")) }
        val request = async { repo.getDashboard() }
        request.join()
        assertTrue(request.isCancelled)
    }

    @Test fun unreadCountHttpFailureIsNotASuccessfulZeroBadge() = runBlocking {
        val api = object : TraewellingApiService by unusedService() {
            override suspend fun getUnreadNotificationCount() =
                Response.error<UnreadCountResponse>(401, "{}".toResponseBody())
        }
        assertTrue(TraewellingRepository(Dao(), { alice }, { api }).getUnreadNotificationCount().isFailure)
    }

    private fun repository(dao: Dao, session: suspend () -> AuthSession,
        response: suspend () -> Response<StatusListResponse>): TraewellingRepository {
        val api = object : TraewellingApiService by unusedService() {
            override suspend fun getDashboard(page: Int) = response()
        }
        return TraewellingRepository(dao, session, { api })
    }

    private fun status(id: Int, body: String) = gson.fromJson(
        "{\"id\":$id,\"body\":\"$body\"}", Status::class.java)
    private fun feed(vararg statuses: Status) = Response.success(StatusListResponse(statuses.toList(), null, null))

    private class Dao : StatusDao {
        val rows = linkedMapOf<Pair<Int, String>, StatusEntity>()
        var replacements = 0
        var reads = 0
        var beforeRead: suspend () -> Unit = {}
        var afterReplace: suspend () -> Unit = {}
        override suspend fun getStatuses(type: String): List<StatusEntity> {
            reads++
            beforeRead()
            return rows.values.filter { it.type == type }
                .sortedWith(compareBy<StatusEntity> { it.position }.thenByDescending { it.id })
        }
        override suspend fun insertStatuses(statuses: List<StatusEntity>) {
            statuses.forEach { rows[it.id to it.type] = it }
        }
        override suspend fun clearStatuses(type: String) {
            rows.keys.removeAll { it.second == type }
        }
        override suspend fun replaceStatuses(type: String, statuses: List<StatusEntity>) {
            replacements++
            clearStatuses(type)
            insertStatuses(statuses)
            afterReplace()
        }
    }
}
