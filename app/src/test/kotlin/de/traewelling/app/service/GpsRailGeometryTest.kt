package de.traewelling.app.service

import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToLong

class GpsRailGeometryTest {
    private val base = 1_800_000_000_000L
    private val route = listOf(
        stop("origin", .0, null, base, origin = true),
        stop("middle", .02, base + 120_000, base + 150_000),
        stop("target", .04, base + 270_000, null, destination = true)
    )
    private val path = listOf(RoutePoint(50.0, .0), RoutePoint(50.005, .0),
        RoutePoint(50.005, .02), RoutePoint(50.0, .02))

    @Test fun curvedTripPolylineSupportsTimesWhereTheStationChordCannot() {
        val estimator = GpsJourneyTimeEstimator()
        var times: GpsJourneyTimes? = null
        for (longitude in listOf(.004, .006, .008)) {
            val fix = curveFix(longitude)
            assertNull(update(GpsJourneyTimeEstimator(), fix, emptyList()))
            times = update(estimator, fix, listOf(shape()))
        }
        assertTrue(kotlin.math.abs(times!!.stopTimes.first().arrivalMillis!! - (base + 180_000)) < 1_000)
        assertEquals(GpsGeometrySource.TRIP_POLYLINE, estimator.geometrySource())
        assertNull(estimator.unavailableReason())
    }

    @Test fun validTripShapeNeverFallsBackToTheOldChordForAnOffRouteFix() {
        val estimator = GpsJourneyTimeEstimator()
        travel(estimator)
        val last = curveFix(.008)
        val onChord = LocationFix(50.0, .008, 10.0, last.timeMillis + 10_000, 12.0)
        assertNull(update(estimator, onChord, listOf(shape())))
        assertEquals(GpsTimeUnavailableReason.OUTSIDE_CORRIDOR, estimator.unavailableReason())
        assertNull(estimator.geometrySource())
        // The old corridor itself would be able to build a forecast here.
        val fallback = GpsJourneyTimeEstimator()
        var result: GpsJourneyTimes? = null
        for (longitude in listOf(.008, .010, .012)) {
            result = update(fallback, onChord.copy(longitude = longitude,
                timeMillis = onChord.timeMillis + ((longitude - .008) * 6_000_000).roundToLong()), emptyList())
        }
        assertNotNull(result)
    }

    @Test fun refreshedTimestampAndFuturePrefetchKeepTheActiveShapeAndOriginalExpiry() {
        val estimator = GpsJourneyTimeEstimator()
        val before = travel(estimator)
        val fix = curveFix(.008)
        val refreshed = shape().copy(geometry = shape().geometry.copy(fetchedAtMillis = fix.timeMillis))
        val future = GpsSegmentGeometry("middle", "target", RouteGeometry(RoutePoint(50.0, .02),
            RoutePoint(50.0, .04), listOf(listOf(RoutePoint(50.0, .02), RoutePoint(50.003, .03),
                RoutePoint(50.0, .04))), base), GpsGeometrySource.TRIP_POLYLINE)
        assertEquals(before, update(estimator, fix, listOf(refreshed, future), now = fix.timeMillis + 1_000))
        assertEquals(before.validUntilMillis, update(estimator, fix, listOf(refreshed), now = fix.timeMillis + 2_000)!!.validUntilMillis)
    }

    @Test fun shapeChangeOrRemovalCannotReuseTheConsumedFixAsNewForecastSupport() {
        for (replacement in listOf(emptyList(), listOf(shape().copy(geometry = shape().geometry.copy(
                alternatives = listOf(path.map { if (it.latitude == 50.005) it.copy(latitude = 50.0051) else it })))))) {
            val estimator = GpsJourneyTimeEstimator()
            travel(estimator)
            assertNull(update(estimator, curveFix(.008), replacement))
            assertEquals(GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT, estimator.unavailableReason())
        }
    }

    @Test fun shapeChangesKeepActualArrivalButDoNotKeepItsFutureForecast() {
        val estimator = GpsJourneyTimeEstimator()
        val atMiddle = LocationFix(50.0, .02, 10.0, base + 180_000, 1.0)
        val arrived = progress().copy(arrivedAtCurrent = true)
        estimator.update(route, arrived, TrackingSource.GPS, atMiddle, atMiddle.timeMillis, listOf(shape()))
        val second = atMiddle.copy(timeMillis = atMiddle.timeMillis + 3_000)
        assertNotNull(estimator.update(route, arrived, TrackingSource.GPS, second, second.timeMillis, listOf(shape())))
        val changed = shape().copy(geometry = shape().geometry.copy(alternatives = listOf(
            path.map { if (it.latitude == 50.005) it.copy(latitude = 50.0051) else it })))
        val actualOnly = estimator.update(route, arrived, TrackingSource.GPS, second, second.timeMillis, listOf(changed))!!
        assertEquals(1, actualOnly.stopTimes.size)
        assertTrue(actualOnly.stopTimes.single().arrivalObserved)
        assertNull(actualOnly.stopTimes.single().departureMillis)
    }

    @Test fun expiredOrAnotherVisitsTripPolylineUsesOnlyTheOptionalRailFallback() {
        val invalid = listOf(shape().copy(fromKey = "different-origin-visit"),
            shape().copy(geometry = shape().geometry.copy(fetchedAtMillis = base - 900_001)))
        for (binding in invalid) {
            val estimator = GpsJourneyTimeEstimator()
            val fix = curveFix(.008)
            assertNull(update(estimator, fix, listOf(binding)))
            assertEquals(GpsTimeUnavailableReason.OUTSIDE_CORRIDOR, estimator.unavailableReason())
            assertNull(estimator.geometrySource())
        }
    }

    @Test fun tripPolylineCannotReplaceTheRequiredSevRoadModel() {
        val estimator = GpsJourneyTimeEstimator()
        val fix = curveFix(.008)
        assertNull(estimator.update(route, progress(), TrackingSource.GPS, fix, fix.timeMillis,
            listOf(shape()), useRoadGeometry = true))
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE, estimator.unavailableReason())
    }

    @Test fun crossingRailChainagesAreRejectedInsteadOfChoosingAStationChord() {
        val crossing = listOf(RoutePoint(50.0, .0), RoutePoint(50.004, .02),
            RoutePoint(50.004, .0), RoutePoint(50.0, .02))
        val binding = shape().copy(geometry = shape().geometry.copy(alternatives = listOf(crossing)))
        val estimator = GpsJourneyTimeEstimator()
        val fix = LocationFix(50.002, .01, 10.0, base + 60_000, 12.0)
        assertNull(update(estimator, fix, listOf(binding)))
        assertEquals(GpsTimeUnavailableReason.AMBIGUOUS_ROUTE, estimator.unavailableReason())
    }

    @Test fun orderedReturnToTheSameStationCanUseARealLoopInsteadOfAZeroLengthChord() {
        val loopPath = path + path.first()
        val loopRoute = route.take(2).mapIndexed { index, stop -> if (index == 0) stop else
            stop.copy(stationId = route.first().stationId, longitude = .0) }
        val binding = shape().copy(geometry = RouteGeometry(loopPath.first(), loopPath.last(), listOf(loopPath), base))
        val north = Math.toRadians(.005) * 6_371_000
        val east = Math.toRadians(.02) * 6_371_000 * cos(Math.toRadians(50.005))
        val southEast = Math.toRadians(.02) * 6_371_000 * cos(Math.toRadians(50.0))
        var result: GpsJourneyTimes? = null
        val estimator = GpsJourneyTimeEstimator()
        for (longitude in listOf(.004, .006, .008)) {
            val fix = LocationFix(50.005, longitude, 10.0, base + 60_000 +
                (120_000 * (north + east * longitude / .02) / (north * 2 + east + southEast)).roundToLong(), 12.0)
            result = estimator.update(loopRoute, progress(), TrackingSource.GPS, fix, fix.timeMillis, listOf(binding))
        }
        assertNotNull(result)
        assertTrue(kotlin.math.abs(result!!.stopTimes.single().arrivalMillis!! - (base + 180_000)) < 1_000)
        assertEquals("middle", result.stopTimes.single().stopKey)
    }

    @Test fun longRailLegCanUseCurvedGeometryWhileKeepingTheNinetyMinuteForecastLimit() {
        val longPath = buildList {
            add(RoutePoint(50.0, .0))
            for (index in 1..10) add(RoutePoint(50.0 + index * .01, .0))
            for (index in 1..90) add(RoutePoint(50.1, index * .01))
            for (index in 9 downTo 0) add(RoutePoint(50.0 + index * .01, .9))
        }
        val longRoute = listOf(route.first(), route[1].copy(longitude = .9,
            plannedArrivalMillis = base + 3_600_000, plannedDepartureMillis = null,
            effectiveArrivalMillis = base + 3_600_000))
        val geometry = GpsSegmentGeometry("origin", "middle", RouteGeometry(longPath.first(), longPath.last(),
            listOf(longPath), base + 900_000), GpsGeometrySource.TRIP_POLYLINE)
        val segment = TrackingRouteGeometry.prepare(geometry.geometry, longRoute[0], longRoute[1], geometry.source, base + 900_000)!!
        assertTrue(segment.paths.single().length > 50_000)
        val estimator = GpsJourneyTimeEstimator()
        var result: GpsJourneyTimes? = null
        for (longitude in listOf(.20, .202, .204)) {
            val point = RoutePoint(50.1, longitude)
            val along = segment.paths.single().lengths.take(30).sum() +
                Math.toRadians(longitude - .20) * 6_371_000 * cos(Math.toRadians(50.1))
            val fraction = along / segment.paths.single().length
            val fix = LocationFix(point.latitude, point.longitude, 10.0,
                base + 60_000 + (3_600_000 * fraction).roundToLong(), 20.0)
            result = estimator.update(longRoute, progress(), TrackingSource.GPS, fix, fix.timeMillis, listOf(geometry))
        }
        assertNotNull(result)
        assertTrue(kotlin.math.abs(result!!.stopTimes.single().arrivalMillis!! - (base + 3_660_000)) < 1_000)
        val tooLong = longRoute.map { if (it.key == "middle") it.copy(plannedArrivalMillis = base + 5_400_001) else it }
        val fix = LocationFix(longPath[32].latitude, longPath[32].longitude, 10.0, base + 1_800_000, 20.0)
        val unsupported = GpsJourneyTimeEstimator()
        assertNull(unsupported.update(tooLong, progress(), TrackingSource.GPS, fix, fix.timeMillis,
            listOf(geometry.copy(geometry = geometry.geometry.copy(fetchedAtMillis = fix.timeMillis)))))
        assertEquals(GpsTimeUnavailableReason.ROUTE_UNSUPPORTED, unsupported.unavailableReason())
    }

    private fun travel(estimator: GpsJourneyTimeEstimator): GpsJourneyTimes {
        var result: GpsJourneyTimes? = null
        for (longitude in listOf(.004, .006, .008)) result = update(estimator, curveFix(longitude), listOf(shape()))
        return result!!
    }

    private fun update(estimator: GpsJourneyTimeEstimator, fix: LocationFix, shapes: List<GpsSegmentGeometry>,
                       now: Long = fix.timeMillis) = estimator.update(route, progress(), TrackingSource.GPS, fix, now, shapes)
    private fun progress() = TrackingProgress(nextIndex = 1, nextStopKey = "middle", gpsEstablished = true)
    private fun shape() = GpsSegmentGeometry("origin", "middle", RouteGeometry(path.first(), path.last(),
        listOf(path), base), GpsGeometrySource.TRIP_POLYLINE)
    private fun curveFix(longitude: Double): LocationFix {
        val north = Math.toRadians(.005) * 6_371_000
        val east = Math.toRadians(.02) * 6_371_000 * cos(Math.toRadians(50.005))
        val along = north + east * longitude / .02
        return LocationFix(50.005, longitude, 10.0, base + 60_000 +
            (120_000 * along / (north * 2 + east)).roundToLong(), 12.0)
    }
    private fun stop(key: String, longitude: Double, arrival: Long?, departure: Long?,
                     origin: Boolean = false, destination: Boolean = false) = TrackingStop(key, key.hashCode(), key,
        50.0, longitude, arrival, arrival, departure, isOrigin = origin, isDestination = destination,
        plannedDepartureMillis = departure)
}
