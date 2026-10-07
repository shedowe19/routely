package de.traewelling.app.service

import de.traewelling.app.data.model.*
import de.traewelling.app.data.routing.TransitRouteParser
import org.junit.Assert.*
import org.junit.Test

/** Integration regressions for ordered GPS progress and physical departure events. */
class GpsOrderedDepartureRegressionTest {
    private val base = 1_800_000_000_000L
    private val metersPerDegree = 111_195.0

    @Test fun frequentMovementOnValidatedCurveMustBootstrapDepartedOrigin() {
        val origin = stop("origin", 50.0, 0.0, true)
        val next = stop("next", 50.0, .02)
        val route = listOf(origin, next)
        val shape = nativeShape(route)
        val engine = StationTrackingEngine(route)
        engine.updateSegmentGeometries(shape.segments)
        // Tracking starts after departure, 222 m north of the origin on a
        // genuinely curved native route; speed is 8 m/s, callbacks are 3 s apart.
        for (sample in 0..2) {
            val fix = LocationFix(50.0 + (222.0 + sample * 24.0) / metersPerDegree,
                0.0, 10.0, base + sample * 3_000L, 8.0)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertFalse(update.destinationReached)
            assertEquals(if (sample < 2) "origin" else "next", engine.getProgress().nextStopKey)
        }
        assertEquals("Supported cumulative movement should select the successor", "next", engine.getProgress().nextStopKey)
    }

    @Test fun frequentMovementOnValidatedCurveMustRecoverAPreviouslyApproachedVisitAfterGap() {
        val old = stop("old", 50.0, 0.0)
        val next = stop("next", 50.0, .02)
        val route = listOf(old, next)
        val engine = StationTrackingEngine(route)
        engine.updateSegmentGeometries(nativeShape(route).segments)
        for ((meters, elapsed) in listOf(-350.0 to 0L, -250.0 to 12_500L)) {
            val fix = LocationFix(50.0 + meters / metersPerDegree, 0.0, 10.0, base + elapsed, 8.0)
            engine.onLocation(fix, fix.timeMillis)
        }
        // A tunnel hides the actual pass; independent post-gap fixes all
        // project forward on the curved route, but each pair is only 24 m.
        for (sample in 0..2) {
            val fix = LocationFix(50.0 + (222.0 + sample * 24.0) / metersPerDegree,
                0.0, 10.0, base + 71_500L + sample * 3_000L, 8.0)
            assertFalse(engine.onLocation(fix, fix.timeMillis).destinationReached)
            assertEquals(if (sample < 2) "old" else "next", engine.getProgress().nextStopKey)
        }
        assertEquals("Supported cumulative movement should recover the next section", "next", engine.getProgress().nextStopKey)
    }

    @Test fun earlyHandoverForCloseStopsMustNotPermanentlyLoseObservedDeparture() {
        val origin = stop("origin", 0.0, 0.0, true).copy(plannedArrivalMillis = null,
            effectiveArrivalMillis = null, plannedDepartureMillis = base, effectiveDepartureMillis = base)
        val next = stop("next", 0.0, 180.0 / metersPerDegree).copy(
            plannedArrivalMillis = base + 25_000, plannedDepartureMillis = base + 55_000)
        val destination = stop("destination", 0.0, 2_000.0 / metersPerDegree).copy(isDestination = true)
        val route = listOf(origin, next, destination)
        val engine = StationTrackingEngine(route)
        val estimator = GpsJourneyTimeEstimator()
        var times: GpsJourneyTimes? = null
        fun accept(position: Double, elapsed: Long, speed: Double) {
            val fix = LocationFix(0.0, position / metersPerDegree, 10.0, base + elapsed, speed)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertFalse(update.destinationReached)
            times = estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis)
        }
        accept(0.0, 0L, 0.0)
        accept(0.0, 3_000L, 0.0)
        for (sample in 1..7) accept(sample * 24.0, 3_000L + sample * 3_000L, 8.0)
        // The short-stop cursor handover occurs around 120 m, while the
        // departure observer still requires > 120 m + 10 m accuracy.
        accept(180.0, 27_000L, 0.0)
        accept(180.0, 30_000L, 0.0)
        assertEquals("next", engine.getProgress().nextStopKey)
        assertTrue(times!!.stopTimes.single { it.stopKey == "next" }.arrivalObserved)
        val departure = times!!.stopTimes.single { it.stopKey == "origin" }
        assertTrue("The origin departure must remain observable after the earlier cursor handover",
            departure.departureObserved)
        assertEquals(base + 21_000L, departure.departureMillis)
    }

    @Test fun ninetyMinuteForecastLimitMustNotHideASupportedObservedRailDeparture() {
        val origin = stop("origin", 50.0, 0.0, true).copy(plannedArrivalMillis = null,
            effectiveArrivalMillis = null, plannedDepartureMillis = base, effectiveDepartureMillis = base)
        val next = stop("next", 50.0, 2.5).copy(plannedArrivalMillis = base + 6_000_000,
            effectiveArrivalMillis = base + 6_000_000, plannedDepartureMillis = null, effectiveDepartureMillis = null,
            isDestination = true)
        val route = listOf(origin, next)
        val request = TransitRouteRequest(42, "trip", route.map {
            TransitRouteVisit(it.key, it.stationId, null, it.plannedArrivalMillis,
                it.plannedDepartureMillis, RoutePoint(it.latitude!!, it.longitude!!))
        })
        val coordinates = buildList {
            add("[0.0,50.0]")
            add("[0.0,50.02]")
            for (index in 1..25) add("[${index / 10.0},50.02]")
            add("[2.5,50.0]")
        }.joinToString(",")
        val json = """{"data":{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"statusId":42},"geometry":{"type":"LineString","coordinates":[$coordinates]}}]}}"""
        val geometry = TransitRouteParser.parse(json, request, base)!!
        val engine = StationTrackingEngine(route)
        engine.updateSegmentGeometries(geometry.segments)
        val estimator = GpsJourneyTimeEstimator()
        var times: GpsJourneyTimes? = null
        fun accept(meters: Double, elapsed: Long, speed: Double) {
            val fix = LocationFix(50.0 + meters / metersPerDegree, 0.0, 10.0, base + elapsed, speed)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertFalse(update.destinationReached)
            times = estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis,
                geometry.segments)
        }
        accept(0.0, 0L, 0.0)
        accept(0.0, 3_000L, 0.0)
        for (sample in 1..15) accept(sample * 24.0, 3_000L + sample * 3_000L, 8.0)
        assertEquals("next", engine.getProgress().nextStopKey)
        assertEquals(GpsTimeUnavailableReason.ROUTE_UNSUPPORTED, estimator.unavailableReason())
        assertTrue("The forecast limit must preserve physically observed departure",
            times?.stopTimes?.any { it.stopKey == "origin" && it.departureObserved } == true)
    }

    @Test fun shortCurvedSectionKeepsItsPendingDepartureAfterNativeCursorHandover() {
        val route = closeStopRoute()
        val from = RoutePoint(0.0, 0.0)
        val middle = RoutePoint(.0002, 90.0 / metersPerDegree)
        val to = RoutePoint(0.0, 180.0 / metersPerDegree)
        val segment = GpsSegmentGeometry("origin", "next", RouteGeometry(from, to,
            listOf(listOf(from, middle, to)), base), GpsGeometrySource.TRIP_POLYLINE)
        val engine = StationTrackingEngine(route)
        engine.updateSegmentGeometries(listOf(segment))
        val estimator = GpsJourneyTimeEstimator()
        val legLength = TrackingRouteGeometry.distance(from, middle)
        var times: GpsJourneyTimes? = null
        fun accept(chainage: Double, elapsed: Long, speed: Double) {
            val firstLeg = chainage <= legLength
            val fraction = if (firstLeg) chainage / legLength else (chainage - legLength) / legLength
            val start = if (firstLeg) from else middle
            val end = if (firstLeg) middle else to
            val fix = LocationFix(start.latitude + (end.latitude - start.latitude) * fraction,
                start.longitude + (end.longitude - start.longitude) * fraction, 10.0, base + elapsed, speed)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertFalse(update.destinationReached)
            times = estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis, listOf(segment))
        }
        accept(0.0, 0L, 0.0)
        accept(0.0, 3_000L, 0.0)
        for (sample in 1..7) accept(sample * 24.0, 3_000L + sample * 3_000L, 8.0)
        assertEquals("next", engine.getProgress().nextStopKey)
        val departure = times!!.stopTimes.single { it.stopKey == "origin" }
        assertTrue(departure.departureObserved)
        assertEquals(base + 21_000L, departure.departureMillis)
    }

    @Test fun absentNextPlannedArrivalMustNotHideSupportedObservedDeparture() {
        val origin = stop("origin", 0.0, 0.0, true).copy(plannedArrivalMillis = null,
            effectiveArrivalMillis = null, plannedDepartureMillis = base, effectiveDepartureMillis = base)
        val next = stop("next", 0.0, 2_000.0 / metersPerDegree).copy(
            plannedArrivalMillis = null, effectiveArrivalMillis = null,
            plannedDepartureMillis = base + 300_000, effectiveDepartureMillis = base + 300_000)
        val route = listOf(origin, next)
        val engine = StationTrackingEngine(route)
        val estimator = GpsJourneyTimeEstimator()
        var times: GpsJourneyTimes? = null
        fun accept(meters: Double, elapsed: Long, speed: Double) {
            val fix = LocationFix(0.0, meters / metersPerDegree, 10.0, base + elapsed, speed)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertFalse(update.destinationReached)
            times = estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis)
        }
        accept(0.0, 0L, 0.0)
        accept(0.0, 3_000L, 0.0)
        for (sample in 1..15) accept(sample * 24.0, 3_000L + sample * 3_000L, 8.0)
        assertEquals("next", engine.getProgress().nextStopKey)
        assertEquals(GpsTimeUnavailableReason.ROUTE_UNSUPPORTED, estimator.unavailableReason())
        assertTrue("Missing forecast arrival input must preserve physically observed departure",
            times?.stopTimes?.any { it.stopKey == "origin" && it.departureObserved } == true)
    }

    @Test fun interruptedNativeCumulativeMovementNeedsNewDirectedEvidence() {
        for (interruption in listOf("accuracy", "backwards", "gap", "form")) {
            val route = listOf(stop("origin", 50.0, 0.0, true), stop("next", 50.0, .02))
            val segments = nativeShape(route).segments
            val engine = StationTrackingEngine(route)
            engine.updateSegmentGeometries(segments)
            fun accept(meters: Double, elapsed: Long, accuracy: Double = 10.0) {
                val fix = LocationFix(50.0 + meters / metersPerDegree, 0.0, accuracy, base + elapsed, 8.0)
                assertFalse(engine.onLocation(fix, fix.timeMillis).destinationReached)
            }
            accept(222.0, 0L)
            accept(246.0, 3_000L)
            when (interruption) {
                "accuracy" -> accept(258.0, 4_500L, 150.0)
                "backwards" -> accept(230.0, 6_000L)
                "form" -> engine.updateSegmentGeometries(segments.map { segment ->
                    segment.copy(geometry = segment.geometry.copy(alternatives =
                        segment.geometry.alternatives.map { points -> points.map { point ->
                            if (point.latitude == 50.005) point.copy(latitude = 50.0051) else point
                        } }))
                })
            }
            val start = if (interruption == "backwards") 254.0 else 270.0
            val elapsed = when (interruption) {
                "gap" -> 40_000L
                "backwards" -> 9_000L
                else -> 6_000L
            }
            accept(start, elapsed)
            assertEquals("$interruption cannot reuse cumulative movement", "origin", engine.getProgress().nextStopKey)
            // The backwards observation itself starts the new supported chain;
            // all other interruptions start it at the first later valid fix.
            accept(start + 24.0, elapsed + 3_000L)
            if (interruption != "backwards") {
                assertEquals("origin", engine.getProgress().nextStopKey)
                accept(start + 48.0, elapsed + 6_000L)
            }
            assertEquals("next", engine.getProgress().nextStopKey)
        }
    }

    @Test fun aTimestampRefreshKeepsCumulativeNativeMovementButAReplayDoesNotAddIt() {
        val route = listOf(stop("origin", 50.0, 0.0, true), stop("next", 50.0, .02))
        val segments = nativeShape(route).segments
        val engine = StationTrackingEngine(route)
        engine.updateSegmentGeometries(segments)
        val first = LocationFix(50.0 + 222.0 / metersPerDegree, 0.0, 10.0, base, 8.0)
        engine.onLocation(first, first.timeMillis)
        val second = first.copy(latitude = 50.0 + 246.0 / metersPerDegree, timeMillis = base + 3_000L)
        engine.onLocation(second, second.timeMillis)
        engine.updateSegmentGeometries(segments.map {
            it.copy(geometry = it.geometry.copy(fetchedAtMillis = base + 3_000L))
        })
        repeat(4) { engine.onLocation(second, base + 4_000L) }
        assertEquals("origin", engine.getProgress().nextStopKey)
        val third = second.copy(latitude = 50.0 + 270.0 / metersPerDegree, timeMillis = base + 6_000L)
        engine.onLocation(third, third.timeMillis)
        assertEquals("next", engine.getProgress().nextStopKey)
    }

    @Test fun pendingDepartureCannotSurviveInvalidFixGapOrProjectionChange() {
        for (interruption in listOf("accuracy", "gap", "form", "backwards")) {
            val route = closeStopRoute()
            val estimator = GpsJourneyTimeEstimator()
            var geometry = emptyList<GpsSegmentGeometry>()
            fun accept(position: Double, elapsed: Long, index: Int, speed: Double,
                       accuracy: Double = 10.0): GpsJourneyTimes? {
                val fix = LocationFix(0.0, position / metersPerDegree, accuracy, base + elapsed, speed)
                return estimator.update(route, TrackingProgress(index, route[index].key,
                    arrivedAtCurrent = index == 0, gpsEstablished = true), TrackingSource.GPS,
                    fix, fix.timeMillis, geometry)
            }
            accept(0.0, 0L, 0, 0.0)
            accept(0.0, 3_000L, 0, 0.0)
            for (sample in 1..4) accept(sample * 24.0, 3_000L + sample * 3_000L, 0, 8.0)
            assertFalse(accept(120.0, 18_000L, 1, 8.0)?.stopTimes?.any { it.departureObserved } == true)
            when (interruption) {
                "accuracy" -> accept(125.0, 19_000L, 1, 8.0, 150.0)
                "backwards" -> accept(96.0, 19_000L, 1, 8.0)
                "form" -> geometry = listOf(GpsSegmentGeometry("origin", "next", RouteGeometry(
                    RoutePoint(0.0, 0.0), RoutePoint(0.0, 180.0 / metersPerDegree),
                    listOf(listOf(RoutePoint(0.0, 0.0), RoutePoint(.0002, 90.0 / metersPerDegree),
                        RoutePoint(0.0, 180.0 / metersPerDegree))), base), GpsGeometrySource.TRIP_POLYLINE))
            }
            val elapsed = if (interruption == "gap") 50_000L else 21_000L
            val result = accept(144.0, elapsed, 1, 8.0)
            assertFalse("$interruption must not borrow the old pending exit proof",
                result?.stopTimes?.any { it.stopKey == "origin" && it.departureObserved } == true)
        }
    }

    @Test fun missingForecastInputDoesNotCreateDepartureWithoutObservedDwell() {
        val route = closeStopRoute().map { if (it.key == "next") it.copy(plannedArrivalMillis = null) else it }
        val estimator = GpsJourneyTimeEstimator()
        for (sample in 0..7) {
            val fix = LocationFix(0.0, sample * 24.0 / metersPerDegree, 10.0, base + sample * 3_000L, 8.0)
            val index = if (sample < 5) 0 else 1
            val result = estimator.update(route, TrackingProgress(index, route[index].key,
                arrivedAtCurrent = false, gpsEstablished = true), TrackingSource.GPS, fix, fix.timeMillis)
            assertFalse(result?.stopTimes?.any { it.departureObserved } == true)
        }
    }

    @Test fun sevDepartureStillNeedsItsPhysicalRoadProjectionWhenForecastInputIsMissing() {
        val route = closeStopRoute().map { if (it.key == "next") it.copy(plannedArrivalMillis = null) else it }
        val estimator = GpsJourneyTimeEstimator()
        fun accept(position: Double, elapsed: Long, index: Int, speed: Double): GpsJourneyTimes? {
            val fix = LocationFix(0.0, position / metersPerDegree, 10.0, base + elapsed, speed)
            return estimator.update(route, TrackingProgress(index, route[index].key,
                arrivedAtCurrent = index == 0, gpsEstablished = true), TrackingSource.GPS,
                fix, fix.timeMillis, useRoadGeometry = true)
        }
        accept(0.0, 0L, 0, 0.0)
        accept(0.0, 3_000L, 0, 0.0)
        for (sample in 1..7) {
            assertFalse(accept(sample * 24.0, 3_000L + sample * 3_000L, if (sample < 5) 0 else 1, 8.0)
                ?.stopTimes?.any { it.departureObserved } == true)
        }
        assertEquals(GpsTimeUnavailableReason.ROUTE_GEOMETRY_UNAVAILABLE, estimator.unavailableReason())
    }

    private fun closeStopRoute() = listOf(
        stop("origin", 0.0, 0.0, true).copy(plannedArrivalMillis = null, effectiveArrivalMillis = null,
            plannedDepartureMillis = base, effectiveDepartureMillis = base),
        stop("next", 0.0, 180.0 / metersPerDegree).copy(plannedArrivalMillis = base + 25_000L,
            plannedDepartureMillis = base + 55_000L),
        stop("destination", 0.0, 2_000.0 / metersPerDegree).copy(isDestination = true)
    )

    private fun nativeShape(route: List<TrackingStop>): TransitRouteGeometry {
        val request = TransitRouteRequest(42, "trip", route.map {
            TransitRouteVisit(it.key, it.stationId, null, it.plannedArrivalMillis,
                it.plannedDepartureMillis, RoutePoint(it.latitude!!, it.longitude!!))
        })
        val json = """{"data":{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"statusId":42},"geometry":{"type":"LineString","coordinates":[[0.0,50.0],[0.0,50.005],[0.02,50.005],[0.02,50.0]]}}]}}"""
        return TransitRouteParser.parse(json, request, base)!!
    }

    private fun stop(key: String, lat: Double, lon: Double, origin: Boolean = false) = TrackingStop(
        key, key.hashCode(), key, lat, lon, base + 300_000L, base + 300_000L, base + 330_000L,
        isOrigin = origin, plannedDepartureMillis = base + 330_000L)
}
