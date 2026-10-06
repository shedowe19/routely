package de.traewelling.app.service

import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Road model provenance and lifecycle must never bypass ordinary GPS evidence. */
class GpsRoadRouteValidationTest {
    private val base = Instant.parse("2026-10-06T10:00:00Z").toEpochMilli()
    private val route = listOf(
        stop("origin", 1, .0, null, base, origin = true),
        stop("middle", 2, .02, base + 120_000, base + 150_000),
        stop("destination", 3, .04, base + 270_000, base + 300_000, destination = true)
    )

    @Test
    fun sevWithoutRoadGeometryDoesNotUseTheOtherwiseSupportedStraightForecast() {
        val straight = travel(GpsJourneyTimeEstimator(), emptyList(), roadMode = false)
        assertNotNull(straight)
        val sev = GpsJourneyTimeEstimator()
        assertNull(travel(sev, emptyList()))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE, sev.unavailableReason())
    }

    @Test
    fun anotherVisitToTheSameStationsCannotSupplyRoadGeometry() {
        val wrongVisit = segment().copy(fromKey = "origin-return", toKey = "middle-return")
        assertRejected(wrongVisit)
    }

    @Test
    fun duplicateBindingsDoNotChooseAnArbitraryRoadModel() {
        val estimator = GpsJourneyTimeEstimator()
        assertNull(travel(estimator, listOf(segment(), segment())))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE, estimator.unavailableReason())
    }

    @Test
    fun roadForDifferentPhysicalStopsIsRejectedDespiteMatchingVisitKeys() {
        val model = geometry().copy(from = RoutePoint(50.0, .00005))
        assertRejected(segment(model))
    }

    @Test
    fun roadSnappedBeyondThePublicStopToleranceCannotSupplyAPrognosis() {
        val model = geometry().copy(alternatives = listOf(listOf(RoutePoint(50.0, .003), point(1))))
        assertRejected(segment(model))
    }

    @Test
    fun expiredRoadGeometryCannotBeRevivedByFreshGps() {
        assertRejected(segment(geometry().copy(fetchedAtMillis = base - 86_400_001)))
    }

    @Test
    fun futureDatedRoadGeometryDoesNotOverrideApiFallback() {
        assertRejected(segment(geometry().copy(fetchedAtMillis = base + 3_600_000)))
    }

    @Test
    fun excessiveVertexCountRejectsAnOtherwisePlausibleRoad() {
        val points = (0..5_000).map { RoutePoint(50.0, .02 * it / 5_000) }
        assertRejected(segment(geometry().copy(alternatives = listOf(points))))
    }

    @Test
    fun invalidVerticesCannotSupplyAPrognosis() {
        val points = listOf(point(0), RoutePoint(Double.NaN, .01), point(1))
        assertRejected(segment(geometry().copy(alternatives = listOf(points))))
    }

    @Test
    fun zeroLengthRoadCannotManufactureSegmentProgress() {
        val collapsedRoute = route.toMutableList().apply { this[1] = this[1].copy(longitude = .0) }
        val collapsed = RoadRouteGeometry(point(0), point(0), listOf(listOf(point(0), point(0))), base)
        val estimator = GpsJourneyTimeEstimator()
        for (time in listOf(30_000L, 36_000L, 42_000L)) {
            assertNull(update(estimator, LocationFix(50.0, .0, 10.0, base + time, 0.0),
                listOf(segment(collapsed)), stops = collapsedRoute))
        }
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE, estimator.unavailableReason())
    }

    @Test
    fun fetchedAtRefreshAlonePreservesAnEstablishedForecastAndItsExpiry() {
        val estimator = GpsJourneyTimeEstimator()
        val original = travel(estimator, listOf(segment()))!!
        val refreshed = segment(geometry().copy(fetchedAtMillis = base + 40_000))
        val retained = update(estimator, fractionFix(.35), listOf(refreshed), now = base + 45_000)
        assertEquals(original, retained)
        assertNull(estimator.unavailableReason())
    }

    @Test
    fun anUnrelatedFuturePrefetchDoesNotInvalidateTheCurrentForecast() {
        val estimator = GpsJourneyTimeEstimator()
        val original = travel(estimator, listOf(segment()))!!
        val prefetched = GpsSegmentGeometry("middle", "destination",
            geometry(index = 2).copy(fetchedAtMillis = base + 3_600_000))
        assertEquals(original, update(estimator, fractionFix(.35), listOf(segment(), prefetched)))
        assertNull(estimator.unavailableReason())
    }

    @Test
    fun changingTheActiveRoadRequiresNewMovementInsteadOfReplayingConsumedFixes() {
        val estimator = GpsJourneyTimeEstimator()
        assertNotNull(travel(estimator, listOf(segment())))
        val changed = segment(bentGeometry())
        repeat(3) { assertNull(update(estimator, fractionFix(.35), listOf(changed))) }
        assertEquals(GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT, estimator.unavailableReason())
        assertNull(update(estimator, fractionFix(.40), listOf(changed)))
        assertNull(update(estimator, fractionFix(.45), listOf(changed)))
        assertNotNull(update(estimator, fractionFix(.50), listOf(changed)))
    }

    @Test
    fun roadChangePreservesConfirmedArrivalButClearsForecastAndConsumedFixEvidence() {
        val estimator = GpsJourneyTimeEstimator()
        val firstArrival = base + 130_000
        val arrivalProgress = progress(arrived = true)
        update(estimator, LocationFix(50.0, .02, 10.0, firstArrival, 1.0),
            listOf(segment()), state = arrivalProgress)
        val confirmed = update(estimator, LocationFix(50.0, .02, 10.0, base + 135_000, 1.0),
            listOf(segment()), state = arrivalProgress)!!
        assertTrue(confirmed.stopTimes.first { it.stopKey == "middle" }.arrivalObserved)
        val secondLeg = segment(geometry(index = 2), index = 2)
        var forecast: GpsJourneyTimes? = null
        for (fraction in listOf(.10, .20, .30)) {
            forecast = update(estimator, fractionFix(fraction, index = 2), listOf(secondLeg), state = progress(2))
        }
        assertNotNull(forecast)
        assertFalse(forecast!!.stopTimes.first { it.stopKey == "destination" }.arrivalObserved)
        val changed = segment(bentGeometry(index = 2), index = 2)
        val consumed = fractionFix(.30, index = 2)
        repeat(3) {
            val actualOnly = update(estimator, consumed, listOf(changed), state = progress(2))!!
            assertNull(actualOnly.stopTimes.firstOrNull { time -> time.stopKey == "destination" })
            val actual = actualOnly.stopTimes.first { time -> time.stopKey == "middle" }
            assertEquals(firstArrival, actual.arrivalMillis)
            assertTrue(actual.arrivalObserved)
        }
        // One genuinely new fix is still insufficient to rebuild a movement window.
        val fresh = update(estimator, fractionFix(.35, index = 2), listOf(changed), state = progress(2))!!
        assertNull(fresh.stopTimes.firstOrNull { it.stopKey == "destination" })
        assertEquals(firstArrival, fresh.stopTimes.first { it.stopKey == "middle" }.arrivalMillis)
    }

    private fun assertRejected(binding: GpsSegmentGeometry) {
        val estimator = GpsJourneyTimeEstimator()
        assertNull(travel(estimator, listOf(binding)))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE, estimator.unavailableReason())
        assertEquals(base + 120_000, route[1].effectiveArrivalMillis)
    }

    private fun travel(estimator: GpsJourneyTimeEstimator, geometry: List<GpsSegmentGeometry>,
        roadMode: Boolean = true): GpsJourneyTimes? {
        var result: GpsJourneyTimes? = null
        for (fraction in listOf(.25, .30, .35)) {
            result = update(estimator, fractionFix(fraction), geometry, roadMode = roadMode)
        }
        return result
    }

    private fun update(estimator: GpsJourneyTimeEstimator, fix: LocationFix,
        geometry: List<GpsSegmentGeometry>, state: TrackingProgress = progress(),
        now: Long = fix.timeMillis, roadMode: Boolean = true,
        stops: List<TrackingStop> = route): GpsJourneyTimes? =
        estimator.update(stops, state, TrackingSource.GPS, fix, now, geometry, roadMode)

    private fun progress(index: Int = 1, arrived: Boolean = false) = TrackingProgress(
        nextIndex = index, nextStopKey = route[index].key, arrivedAtCurrent = arrived, gpsEstablished = true)

    private fun fractionFix(fraction: Double, index: Int = 1): LocationFix {
        val from = route[index - 1]
        val to = route[index]
        val departure = from.plannedDepartureMillis!!
        val time = departure + ((to.plannedArrivalMillis!! - departure) * fraction).toLong()
        return LocationFix(50.0, from.longitude!! + (to.longitude!! - from.longitude!!) * fraction,
            10.0, time, 12.0)
    }

    private fun geometry(index: Int = 1) = RoadRouteGeometry(point(index - 1), point(index),
        listOf(listOf(point(index - 1), point(index))), base)

    private fun bentGeometry(index: Int = 1) = geometry(index).copy(alternatives = listOf(listOf(
        point(index - 1), RoutePoint(50.0001, (route[index - 1].longitude!! + route[index].longitude!!) / 2),
        point(index))))

    private fun segment(model: RoadRouteGeometry = geometry(), index: Int = 1) =
        GpsSegmentGeometry(route[index - 1].key, route[index].key, model)

    private fun point(index: Int) = RoutePoint(route[index].latitude!!, route[index].longitude!!)

    private fun stop(key: String, id: Int, longitude: Double, arrival: Long?, departure: Long?,
        origin: Boolean = false, destination: Boolean = false) = TrackingStop(
        key, id, key, 50.0, longitude, arrival, arrival, departure,
        isOrigin = origin, isDestination = destination, plannedDepartureMillis = departure)
}
