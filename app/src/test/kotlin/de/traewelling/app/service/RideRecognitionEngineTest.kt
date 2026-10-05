package de.traewelling.app.service

import de.traewelling.app.data.model.DepartureTrip
import de.traewelling.app.data.model.HafasLine
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.data.model.TripDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.cos

/** Synthetic GPS/visits: no network, credentials, device locations or Android dependencies. */
class RideRecognitionEngineTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val latitude = 52.0
    private val longitude = 13.0

    @Test fun directedMovementFromBoardingStationProducesSuggestion() {
        val engine = prepared()
        val matches = movement(engine)
        assertEquals(1, matches.size)
        assertEquals("visit-a", matches.single().ride.origin?.uuid)
        assertEquals("Ziel", matches.single().nextStationName)
    }

    @Test fun stationaryWaitingAndGpsJitterNeverCountAsBoarding() {
        val engine = prepared()
        engine.onLocation(fix(0.0, now - 20_000), now - 20_000)
        engine.onLocation(fix(8.0, now - 10_000), now - 10_000)
        assertTrue(engine.onLocation(fix(-5.0, now), now).isEmpty())
    }

    @Test fun walkingToStationDoesNotMatchTrain() {
        val engine = prepared()
        repeat(11) { index ->
            val time = now - 100_000 + index * 10_000
            assertTrue(engine.onLocation(fix(index * 8.0, time), time).isEmpty())
        }
    }

    @Test fun movementInOppositeDirectionIsRejected() {
        val engine = prepared()
        engine.onLocation(fix(0.0, now - 20_000), now - 20_000)
        engine.onLocation(fix(-120.0, now - 10_000), now - 10_000)
        assertTrue(engine.onLocation(fix(-240.0, now), now).isEmpty())
    }

    @Test fun perpendicularRouteIsRejected() {
        val engine = prepared()
        engine.onLocation(fix(0.0, now - 20_000), now - 20_000)
        engine.onLocation(fix(0.0, now - 10_000, north = 200.0), now - 10_000)
        assertTrue(engine.onLocation(fix(0.0, now, north = 400.0), now).isEmpty())
    }

    @Test fun locationWithoutObservedBoardingStationIsInsufficient() {
        val engine = prepared()
        engine.onLocation(fix(800.0, now - 20_000), now - 20_000)
        engine.onLocation(fix(1_000.0, now - 10_000), now - 10_000)
        assertTrue(engine.onLocation(fix(1_200.0, now), now).isEmpty())
    }

    @Test fun multipleIndistinguishableLinesRemainSeparateSuggestions() {
        val engine = prepared(listOf(ride("A"), ride("B")))
        assertEquals(setOf("A", "B"), movement(engine).map { it.ride.departure.line?.name }.toSet())
    }

    @Test fun cancelledDepartureAndCancelledBoardingVisitAreRejected() {
        val base = ride()
        val cancelledDeparture = base.copy(departure = base.departure.copy(cancelled = true))
        val cancelledOrigin = base.copy(trip = base.trip.copy(stopovers = base.trip.stopovers!!.mapIndexed { index, stop ->
            if (index == 0) stop.copy(cancelled = true) else stop
        }))
        assertTrue(movement(prepared(listOf(cancelledDeparture))).isEmpty())
        assertTrue(movement(prepared(listOf(cancelledOrigin))).isEmpty())
    }

    @Test fun unrelatedTimetableWindowIsRejected() {
        val base = ride()
        val later = base.copy(trip = base.trip.copy(stopovers = base.trip.stopovers!!.mapIndexed { index, stop ->
            if (index == 0) stop.copy(departurePlanned = iso(now + 20 * minute)) else stop
        }))
        assertTrue(movement(prepared(listOf(later))).isEmpty())
    }

    @Test fun realTimeSupersedesOutdatedPlannedTime() {
        val base = ride()
        val delayed = base.copy(trip = base.trip.copy(stopovers = base.trip.stopovers!!.mapIndexed { index, stop ->
            if (index == 0) stop.copy(departurePlanned = iso(now - 30 * minute), departureReal = iso(now - 20_000)) else stop
        }))
        assertEquals(1, movement(prepared(listOf(delayed))).size)
    }

    @Test fun freshDepartureTimeOverridesOlderCachedRouteTime() {
        val base = ride()
        val refreshed = base.copy(departure = base.departure.copy(realWhen = iso(now - 20_000)),
            trip = base.trip.copy(stopovers = base.trip.stopovers!!.mapIndexed { index, stop ->
                if (index == 0) stop.copy(departurePlanned = iso(now - 30 * minute), departureReal = iso(now - 20 * minute)) else stop
            }))
        assertEquals(1, movement(prepared(listOf(refreshed))).size)
    }

    @Test fun inaccurateNewFixHidesExistingMatchUntilReliableMovementResumes() {
        val engine = prepared()
        assertEquals(1, movement(engine).size)
        assertTrue(engine.onLocation(fix(360.0, now + 10_000, accuracy = 100.0), now + 10_000).isEmpty())
        assertTrue(engine.matches(now + 10_000).isEmpty())
        assertEquals(1, engine.onLocation(fix(400.0, now + 20_000), now + 20_000).size)
    }

    @Test fun staleAndFutureLocationsCannotProduceMatches() {
        val engine = prepared()
        movement(engine)
        assertTrue(engine.matches(now + 31_000).isEmpty())
        assertTrue(engine.onLocation(fix(500.0, now + 100_000), now + 40_000).isEmpty())
    }

    @Test fun gpsGapNeedsFreshMotionInsteadOfOldAnchor() {
        val engine = prepared()
        movement(engine)
        assertTrue(engine.onLocation(fix(450.0, now + 60_000), now + 60_000).isEmpty())
        assertTrue(engine.onLocation(fix(600.0, now + 70_000), now + 70_000).isEmpty())
        assertTrue(engine.onLocation(fix(750.0, now + 80_000), now + 80_000).isEmpty())
    }

    @Test fun lateBatchFixCannotRewindMatchedMovement() {
        val engine = prepared()
        movement(engine)
        val unchanged = engine.onLocation(fix(-500.0, now - 5_000), now)
        assertEquals(1, unchanged.size)
        assertEquals(now, unchanged.single().latestFixMillis)
    }

    @Test fun repeatedStationRequiresExactConcreteVisit() {
        val first = ride().trip.stopovers!!.first()
        val loop = listOf(first, StopStation(station = station(2, "Andere", 1_000.0)),
            first.copy(uuid = "visit-a-return", departurePlanned = iso(now + 10 * minute)))
        val departure = ride().departure.copy(plannedWhen = iso(now + 10 * minute))
        assertEquals(2, RideRecognitionEngine.resolveOriginIndex(loop, departure, first.station!!))
        assertEquals(-1, RideRecognitionEngine.resolveOriginIndex(loop,
            departure.copy(plannedWhen = iso(now + 20 * minute)), first.station!!))
    }

    @Test fun routeExpiryAndCancellationInvalidatePreviouslyMatchingService() {
        val engine = prepared()
        assertEquals(1, movement(engine).size)
        engine.removeTrips(setOf("trip-A"))
        assertTrue(engine.matches(now).isEmpty())
        engine.updateRides(listOf(ride()), now)
        assertEquals(1, engine.matches(now).size)
        assertTrue(engine.matches(now + RideRecognitionEngine.ROUTE_TTL_MILLIS + 1).isEmpty())
    }

    @Test fun fixesAndRouteCacheAreBoundedAndResetClearsSession() {
        val engine = prepared((1..40).map { ride("$it") })
        repeat(100) { index -> engine.onLocation(fix(0.0, now + index * 1_000L), now + index * 1_000L) }
        assertTrue(engine.storedFixCount <= RideRecognitionEngine.MAX_FIXES)
        assertTrue(engine.storedRouteCount <= RideRecognitionEngine.MAX_ROUTES)
        engine.clear()
        assertEquals(0, engine.storedFixCount)
        assertEquals(0, engine.storedRouteCount)
        assertTrue(engine.matches(now + 100_000).isEmpty())
    }

    @Test fun expiredNetworkResultsCannotRestoreOldRoutes() {
        val engine = RideRecognitionEngine()
        engine.updateRides(listOf(ride().copy(fetchedAtMillis = now - RideRecognitionEngine.ROUTE_TTL_MILLIS - 1)), now)
        assertEquals(0, engine.storedRouteCount)
        assertTrue(movement(engine).isEmpty())
    }

    @Test fun accumulatedSlowVehicleMovementUsesLongerFreshObservation() {
        val engine = prepared()
        var matches = emptyList<RecognizedRide>()
        repeat(6) { index ->
            val time = now - 25_000 + index * 5_000L
            matches = engine.onLocation(fix(index * 20.0, time), time)
        }
        assertEquals(1, matches.size)
    }

    @Test fun implausibleTeleportationIsRejected() {
        val engine = prepared()
        engine.onLocation(fix(0.0, now - 10_000), now - 10_000)
        engine.onLocation(fix(800.0, now - 5_000), now - 5_000)
        assertTrue(engine.onLocation(fix(1_600.0, now), now).isEmpty())
    }

    @Test fun onlyTwoFixesNeverProduceSuggestion() {
        val engine = prepared()
        engine.onLocation(fix(0.0, now - 20_000), now - 20_000)
        assertTrue(engine.onLocation(fix(240.0, now), now).isEmpty())
    }

    private fun prepared(rides: List<RecognizableRide> = listOf(ride())) = RideRecognitionEngine().apply {
        updateRides(rides, now)
    }
    private fun movement(engine: RideRecognitionEngine): List<RecognizedRide> {
        engine.onLocation(fix(0.0, now - 20_000), now - 20_000)
        engine.onLocation(fix(120.0, now - 10_000), now - 10_000)
        return engine.onLocation(fix(240.0, now), now)
    }
    private fun ride(line: String = "A"): RecognizableRide {
        val origin = station(1, "Start", 0.0)
        val stops = listOf(StopStation(uuid = "visit-a", station = origin, departurePlanned = iso(now - 20_000)),
            StopStation(uuid = "visit-b", station = station(2, "Ziel", 2_000.0), arrivalPlanned = iso(now + 3 * minute)))
        val departure = DepartureTrip("trip-$line", HafasLine(line, null, "bus", null), "Ziel",
            iso(now - 20_000), null, null, null, false, origin)
        return RecognizableRide(departure, TripDetails(1, line, "bus", stops), 0, now)
    }
    private fun station(id: Int, name: String, east: Double) = TrainStation(id = id, name = name,
        latitude = latitude, longitude = longitude + east / (111_320.0 * cos(Math.toRadians(latitude))))
    private fun fix(east: Double, time: Long, north: Double = 0.0, accuracy: Double = 8.0) = LocationFix(
        latitude + north / 111_320.0,
        longitude + east / (111_320.0 * cos(Math.toRadians(latitude))), accuracy, time)
    private fun iso(time: Long) = Instant.ofEpochMilli(time).toString()
}
