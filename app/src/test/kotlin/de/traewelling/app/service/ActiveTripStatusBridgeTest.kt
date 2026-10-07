package de.traewelling.app.service

import com.google.gson.Gson
import de.traewelling.app.data.model.Status
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.data.repository.StatusMutationSnapshot
import org.junit.Assert.*
import org.junit.Test

class ActiveTripStatusBridgeTest {
    private val gson = Gson()
    private val origin = stop("origin", 1, -1500.0)
    private val oldTarget = stop("old-target", 2, 0.0)
    private val newTarget = stop("new-target", 3, 2000.0)
    private val fullRoute = listOf(origin, oldTarget, newTarget)

    private fun stop(key: String, id: Int, meters: Double) = StopStation(uuid = key,
        station = TrainStation(id = id, name = key, latitude = 0.0, longitude = meters / 111_195.0))

    private fun status(target: StopStation, trip: Int = 9, arrival: String? = null): Status = gson.fromJson(
        """{"id":42,"checkin":{"trip":$trip,"tripUuid":"trip-$trip","origin":${gson.toJson(origin)},"destination":${gson.toJson(target)},"manualArrival":${gson.toJson(arrival)}}}""",
        Status::class.java)

    @Test fun committedExtensionRebasesACompletedOldDestinationToAnOrdinaryVisitedStop() {
        val old = status(oldTarget)
        val oldProgress = TrackingProgress(nextIndex = 1, nextStopKey = oldTarget.uuid,
            arrivedAtCurrent = true, completed = true, gpsEstablished = true, announcedKeys = setOf("origin", "old-target"))
        val updated = prepareActiveTripRoute(status(newTarget), fullRoute, old.checkin,
            listOf(origin, oldTarget), oldProgress)!!
        assertTrue(updated.boundariesChanged)
        assertFalse(updated.tripChanged)
        assertEquals(fullRoute, updated.stops)
        assertEquals("old-target", updated.rebasedProgress?.nextStopKey)
        assertTrue(updated.rebasedProgress!!.arrivedAtCurrent)
        assertFalse(updated.rebasedProgress.completed)
        assertEquals(oldProgress.announcedKeys, updated.rebasedProgress.announcedKeys)
        val engine = StationTrackingEngine(updated.stops.mapIndexed { i, stop -> TrackingStop(
            activeTripStopKey(stop, i), stop.stationId, stop.stationName!!, stop.station?.latitude,
            stop.station?.longitude, null, null, null, isOrigin = i == 0, isDestination = i == 2)
        }, updated.rebasedProgress)
        assertFalse(engine.onTimetable(10_000).destinationReached)
        assertFalse(engine.onLocation(LocationFix(0.0, -80.0 / 111_195.0, 10.0, 10_000, 1.0), 10_000).destinationReached)
    }

    @Test fun aReadWithOldStatusButLaterStopoversCannotAuthorizeTheOldDestination() {
        val bridge = ActiveTripStatusBridge().apply { bind(42, "login"); adopt(1) }
        val current = StatusMutationSnapshot(2, StatusMutation.Updated("login", status(newTarget)))
        assertFalse(bridge.acceptsRead(1, current))
        assertFalse(bridge.canPublish(current))
        assertEquals(newTarget.uuid, bridge.replacement(current)?.checkin?.destination?.uuid)
        bridge.adopt(2)
        assertTrue(bridge.canPublish(current))
    }

    @Test fun missingNewDestinationInTheCachedFullRouteRequiresFreshStopovers() {
        val old = status(oldTarget)
        assertNull(prepareActiveTripRoute(status(newTarget), listOf(origin, oldTarget), old.checkin,
            listOf(origin, oldTarget), TrackingProgress(nextIndex = 1, nextStopKey = "old-target")))
    }

    @Test fun sameNumberOnAnotherLoginCannotSupplyAReplacementOrDelete() {
        val bridge = ActiveTripStatusBridge().apply { bind(42, "current-login"); adopt(0) }
        assertNull(bridge.replacement(StatusMutationSnapshot(1, StatusMutation.Updated("old-login", status(newTarget)))))
        assertFalse(bridge.wasDeleted(StatusMutationSnapshot(1, StatusMutation.Deleted("old-login", 42))))
        assertFalse(bridge.owns(StatusMutation.Deleted("current-login", 99)))
        bridge.clear()
        assertFalse(bridge.owns(StatusMutation.Deleted("current-login", 42)))
    }

    @Test fun aFreshPostMutationReadCanReplaceThePutSnapshotWithNewRealtimeData() {
        val old = status(oldTarget)
        val bridge = ActiveTripStatusBridge().apply { bind(42, "login"); adopt(2) }
        val current = StatusMutationSnapshot(2, StatusMutation.Updated("login", status(newTarget)))
        assertTrue(bridge.acceptsRead(2, current))
        val fresh = prepareActiveTripRoute(status(newTarget, arrival = "2026-10-07T08:37:00Z"), fullRoute,
            old.checkin, listOf(origin, oldTarget), TrackingProgress())!!
        assertEquals("2026-10-07T08:37:00Z", fresh.stops.last().arrivalReal)
    }

    @Test fun changingTheTripResetsVisitProgressInsteadOfReusingAnotherTrainCursor() {
        val old = status(oldTarget)
        val updated = prepareActiveTripRoute(status(newTarget, trip = 10), fullRoute, old.checkin,
            listOf(origin, oldTarget), TrackingProgress(1, "old-target", true, setOf("old-target"), true, true))!!
        assertTrue(updated.tripChanged)
        assertEquals(TrackingProgress(), updated.rebasedProgress)
    }

    @Test fun evictedOrPreviousLoginMutationCannotAuthorizeThePersistedOldDestination() {
        val bridge = ActiveTripStatusBridge().apply { bind(42, "current-login") }
        assertTrue(bridge.acceptsRestoredCache(StatusMutationSnapshot(0, null)))
        val unknownAfterEviction = StatusMutationSnapshot(66, null)
        assertFalse(bridge.acceptsRestoredCache(unknownAfterEviction))
        assertFalse(bridge.canPublish(unknownAfterEviction))
        assertFalse(bridge.acceptsRestoredCache(StatusMutationSnapshot(1,
            StatusMutation.Updated("previous-login", status(newTarget)))))
    }
}
