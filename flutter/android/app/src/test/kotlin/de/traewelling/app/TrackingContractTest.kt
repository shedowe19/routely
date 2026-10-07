package de.traewelling.app

import org.junit.Assert.*
import org.junit.Test

class TrackingContractTest {
    private fun trip(revision: String, generation: Long, id: Int = 42) =
        mapOf<String, Any?>("sessionRevision" to revision, "statusId" to id, "generation" to generation)

    @Test fun coarseLocationAllowsLookupAndTrackingButCannotAuthorizeRecognition() {
        assertTrue(TrackingContract.permitsLocation(precise = false, approximate = true))
        assertTrue(TrackingContract.permitsLocation(precise = true, approximate = false))
        assertFalse(TrackingContract.permitsLocation(precise = false, approximate = false))
        assertFalse(TrackingContract.permitsLocation(precise = false, approximate = true, recognition = true))
        assertTrue(TrackingContract.permitsLocation(precise = true, approximate = true, recognition = true))
        assertTrue(TrackingContract.permitsLocation(precise = true, approximate = false, recognition = true))
        assertFalse(TrackingContract.permitsLocation(precise = false, approximate = false, recognition = true))
    }

    @Test fun nearbyStationLookupRejectsOldAndFutureCachedLocations() {
        val now = 100_000_000_000L
        assertTrue(TrackingContract.isFreshLocation(now, now))
        assertTrue(TrackingContract.isFreshLocation(now - 30_000_000_000L, now))
        assertFalse(TrackingContract.isFreshLocation(now - 30_000_000_001L, now))
        assertFalse(TrackingContract.isFreshLocation(now + 1, now))
        assertFalse(TrackingContract.isFreshLocation(0, now))
        assertFalse(TrackingContract.isFreshLocation(-1, now))
    }

    @Test fun oldLogoutOrNotificationCannotStopTheSameStatusOnANewAccount() {
        val old = trip("old-account", 8)
        val active = trip("new-account", 9)
        assertFalse(TrackingContract.matches(active, old))
        assertFalse(TrackingContract.matches(active, trip("new-account", 8)))
        assertFalse(TrackingContract.matches(active, mapOf("statusId" to 42, "generation" to 9)))
        assertTrue(TrackingContract.matches(active, active))
    }
    @Test fun delayedStartCannotReplaceNewOwnerOrResurrectRetiredGeneration() {
        val active = trip("new-account", 9)
        assertFalse(TrackingContract.acceptsStart(active, 9, trip("old-account", 8)))
        assertFalse(TrackingContract.acceptsStart(null, 9, active))
        assertTrue(TrackingContract.acceptsStart(null, 9, trip("new-account", 10)))
    }
    @Test fun recognitionHasExplicitModeAndCanNeverMasqueradeAsACheckIn() {
        val recognition = trip("account", 10, 0)
        assertFalse(TrackingContract.acceptsStart(null, 9, recognition))
        assertTrue(TrackingContract.acceptsStart(null, 9, recognition + mapOf("mode" to "recognition")))
    }
    @Test fun credentialsCannotLeakInsidePersistedRouteOrNestedSettings() {
        val valid = trip("account", 10)
        assertFalse(TrackingContract.acceptsStart(null, 9, valid + mapOf("route" to mapOf("auth" to mapOf("access_token" to "not-a-real-token")))))
        assertTrue(TrackingContract.acceptsStart(null, 9, valid + mapOf("route" to mapOf("stops" to listOf(mapOf("latitude" to 50.0, "longitude" to 6.0))))))
    }
    @Test fun settingsRestartKeepsAlertDedupeButAccountAndRouteEditsResetIt() {
        val route = mapOf("route" to mapOf("journey" to mapOf("tripIdentity" to "trip-1", "contentRevision" to 3)))
        val old = trip("account", 10) + route
        assertTrue(TrackingContract.sameAlertOwner(old, trip("account", 11) + route))
        assertFalse(TrackingContract.sameAlertOwner(old, trip("other-account", 11) + route))
        assertFalse(TrackingContract.sameAlertOwner(old, trip("account", 11) + mapOf("route" to mapOf("journey" to mapOf("tripIdentity" to "trip-1", "contentRevision" to 4)))))
    }
}
