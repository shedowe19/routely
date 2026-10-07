package de.traewelling.app.service

import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RoadRouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.routing.RoadRouteParser
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToLong

/** Public road geometry and synthetic fresh fixes; no recorded user location or trajectory. */
class GpsRoadConsensusTest {
    private val base = 1_800_000_000_000L
    private val travel = 1_380_000L
    private val from = RoutePoint(51.43175246, 6.88538831)
    private val to = RoutePoint(51.45018831, 7.0101172)

    @Test
    fun mergedPublicMuelheimEssenRoadsForecastWithoutGuessingAnEarlierApproach() {
        val geometry = publicGeometry()
        val route = publicRoute()
        val estimator = GpsJourneyTimeEstimator()
        val fixes = publicFixes(geometry)
        val segment = binding(geometry)
        val prepared = TrackingRouteGeometry.prepare(geometry, route[0], route[1], segment.source, base)!!
        val projections = prepared.paths.map { TrackingRouteGeometry.project(it, fixes.last()).projection!! }
        assertTrue("Distinct earlier approaches differ by more than 800 m of chainage",
            projections.maxOf { it.fraction * it.length } - projections.minOf { it.fraction * it.length } > 800)
        val spread = (projections.maxOf { it.fraction } - projections.minOf { it.fraction }) * travel
        assertTrue("The public shared suffix has approximately 38 seconds of ETA disagreement", spread in 37_000.0..40_000.0)
        var forecast: GpsJourneyTimes? = null
        fixes.forEachIndexed { index, fix ->
            forecast = update(estimator, route, segment, fix)
            if (index < 2) assertNull("Consensus still requires a complete movement window", forecast)
        }
        val time = forecast!!.stopTimes.single()
        val laterEta = fixes.last().timeMillis + ((1 - projections.minOf { it.fraction }) * travel).roundToLong()
        assertEquals(laterEta, time.arrivalMillis!!)
        assertFalse(time.arrivalObserved)
        assertNull(estimator.unavailableReason())
        assertEquals(base + travel, route[1].effectiveArrivalMillis)
    }

    @Test
    fun changingAlternativeOrderCannotSelectAnEarlierConsensusEta() {
        val geometry = publicGeometry()
        val results = listOf(geometry, geometry.copy(alternatives = geometry.alternatives.reversed())).map { candidate ->
            val estimator = GpsJourneyTimeEstimator()
            publicFixes(geometry).map { update(estimator, publicRoute(), binding(candidate), it) }.last()!!.stopTimes
        }
        assertEquals(results[0], results[1])
    }

    @Test
    fun sharedRemainingRoadStillRejectsMoreThanOneMinuteOfForecastDisagreement() {
        val geometry = publicGeometry()
        val estimator = GpsJourneyTimeEstimator()
        publicFixes(geometry).forEach { fix ->
            assertNull(update(estimator, publicRoute(3_000_000), binding(geometry), fix))
            assertEquals(GpsTimeUnavailableReason.AMBIGUOUS_ROUTE, estimator.unavailableReason())
        }
    }

    @Test
    fun metreAgreementCannotHideMoreThanOneMinuteOfTimeDisagreementOnASlowSection() {
        val route = simpleRoute().mapIndexed { index, stop ->
            if (index == 1) stop.copy(plannedArrivalMillis = base + 5_400_000,
                effectiveArrivalMillis = base + 5_400_000) else stop
        }
        val shape = RoadRouteGeometry(point(route[0]), point(route[1]),
            listOf(curvedPath(.005), curvedPath(.006)), base)
        val fix = LocationFix(50.005, .004, 75.0, base + 60_000, 12.0)
        val prepared = TrackingRouteGeometry.prepare(shape, route[0], route[1], binding(shape).source, base)!!
        val projections = prepared.paths.map { TrackingRouteGeometry.project(it, fix).projection!! }
        assertTrue(projections.maxOf { it.fraction * it.length } -
            projections.minOf { it.fraction * it.length } < 150)
        assertTrue((projections.maxOf { it.fraction } - projections.minOf { it.fraction }) * 5_400_000 > 60_000)
        val estimator = GpsJourneyTimeEstimator()
        assertNull(update(estimator, route, binding(shape), fix))
        assertEquals(GpsTimeUnavailableReason.AMBIGUOUS_ROUTE, estimator.unavailableReason())
    }

    @Test
    fun matchingCurrentRoadCannotResolveAFutureDiversionWithSimilarArrivalFractions() {
        val geometry = publicGeometry()
        val changed = geometry.copy(alternatives = geometry.alternatives.mapIndexed { alternative, points ->
            if (alternative == 0) points else points.mapIndexed { index, point ->
                if (index == 250) point.copy(latitude = point.latitude + .001) else point
            }
        })
        val estimator = GpsJourneyTimeEstimator()
        publicFixes(geometry).forEach { fix ->
            assertNull(update(estimator, publicRoute(), binding(changed), fix))
            assertEquals(GpsTimeUnavailableReason.AMBIGUOUS_ROUTE, estimator.unavailableReason())
        }
    }

    @Test
    fun sharedSuffixNeverRelaxesAmbiguityWithoutAKnownValidTravelInterval() {
        val geometry = publicGeometry()
        val route = publicRoute().mapIndexed { index, stop ->
            if (index == 1) stop.copy(plannedArrivalMillis = null) else stop
        }
        val estimator = GpsJourneyTimeEstimator()
        publicFixes(geometry).forEach { assertNull(update(estimator, route, binding(geometry), it)) }
        assertEquals(GpsTimeUnavailableReason.ROUTE_UNSUPPORTED, estimator.unavailableReason())
    }

    @Test
    fun aConsensusDoesNotTurnAReversedFixIntoSupportedProgress() {
        val geometry = publicGeometry()
        val estimator = GpsJourneyTimeEstimator()
        val established = establishPublic(estimator, geometry)
        val reversed = publicFixes(geometry).first().copy(timeMillis = established.updatedAtMillis + 10_000)
        assertNull(update(estimator, publicRoute(), binding(geometry), reversed))
        assertEquals(GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT, estimator.unavailableReason())
    }

    @Test
    fun aConsensusDoesNotTurnAShortJumpIntoSupportedProgress() {
        val geometry = publicGeometry()
        val estimator = GpsJourneyTimeEstimator()
        val established = establishPublic(estimator, geometry)
        val point = geometry.alternatives.first()[215]
        val jumped = LocationFix(point.latitude, point.longitude, 10.0, established.updatedAtMillis + 1_000, 12.0)
        assertNull(update(estimator, publicRoute(), binding(geometry), jumped))
        assertEquals(GpsTimeUnavailableReason.UNPLAUSIBLE_MOVEMENT, estimator.unavailableReason())
    }

    @Test
    fun inaccurateOrStaleConsensusFixesCannotRetainTheForecast() {
        val geometry = publicGeometry()
        for (inaccurate in listOf(false, true)) {
            val estimator = GpsJourneyTimeEstimator()
            val established = establishPublic(estimator, geometry)
            val fix = publicFixes(geometry).last().copy(timeMillis = established.updatedAtMillis + 1_000,
                accuracyMeters = if (inaccurate) 76.0 else 10.0)
            assertNull(update(estimator, publicRoute(), binding(geometry), fix,
                now = fix.timeMillis + if (inaccurate) 0 else 30_001))
            assertEquals(if (inaccurate) GpsTimeUnavailableReason.INACCURATE_LOCATION else
                GpsTimeUnavailableReason.NO_FRESH_LOCATION, estimator.unavailableReason())
        }
    }

    @Test
    fun replayedConsensusFixesDoNotRenewExpiryOrCreateNewMovement() {
        val geometry = publicGeometry()
        val estimator = GpsJourneyTimeEstimator()
        val established = establishPublic(estimator, geometry)
        val fix = publicFixes(geometry).last()
        assertEquals(established, update(estimator, publicRoute(), binding(geometry), fix, fix.timeMillis + 10_000))
        assertEquals(fix.timeMillis + 30_000, established.validUntilMillis)
        assertNull(update(estimator, publicRoute(), binding(geometry), fix, fix.timeMillis + 30_001))
    }

    @Test
    fun candidateSetChangeNeedsANewWindowAndCannotExtendAnOldForecast() {
        val route = simpleRoute()
        val lower = curvedPath(.005)
        val upper = curvedPath(.00535)
        val segment = binding(RoadRouteGeometry(point(route[0]), point(route[1]), listOf(lower, upper), base))
        val estimator = GpsJourneyTimeEstimator()
        var established: GpsJourneyTimes? = null
        for ((index, longitude) in listOf(.004, .006, .008).withIndex()) {
            established = update(estimator, route, segment,
                LocationFix(50.005, longitude, 40.0, base + 60_000 + index * 5_000, 12.0))
        }
        val forecast = established!!
        for ((time, longitude) in listOf(73_000L to .00805, 78_000L to .0081, 83_000L to .0082)) {
            val retained = update(estimator, route, segment, LocationFix(50.005, longitude, 10.0, base + time, .5))!!
            assertEquals(forecast.updatedAtMillis, retained.updatedAtMillis)
            assertEquals(forecast.validUntilMillis, retained.validUntilMillis)
        }
        assertNull(update(estimator, route, segment, LocationFix(50.005, .00825, 10.0, base + 101_000, .5)))
        assertEquals(GpsTimeUnavailableReason.INSUFFICIENT_MOVEMENT, estimator.unavailableReason())
    }

    @Test
    fun aSpatiallyUnsupportedParallelRoadCannotBlockTheNearerRoadMovementWindow() {
        val route = simpleRoute()
        val shape = RoadRouteGeometry(point(route[0]), point(route[1]),
            listOf(curvedPath(.005), curvedPath(.0057)), base)
        val estimator = GpsJourneyTimeEstimator()
        var forecast: GpsJourneyTimes? = null
        for ((index, longitude) in listOf(.004, .006, .008).withIndex()) {
            forecast = update(estimator, route, binding(shape),
                LocationFix(50.005, longitude, 10.0, base + 60_000 + index * 5_000, 12.0))
        }
        assertNotNull(forecast)
        assertNull(estimator.unavailableReason())
    }

    @Test
    fun aProvenParallelRoadCannotRetainALockWhenAnotherRoadIsClearlyNearer() {
        val route = simpleRoute()
        val shape = RoadRouteGeometry(point(route[0]), point(route[1]),
            listOf(curvedPath(.005), curvedPath(.0057)), base)
        val estimator = GpsJourneyTimeEstimator()
        for ((index, longitude) in listOf(.004, .006, .008).withIndex()) {
            val forecast = update(estimator, route, binding(shape),
                LocationFix(50.005, longitude, 10.0, base + 60_000 + index * 5_000, 12.0))
            if (index == 2) assertNotNull(forecast)
        }
        for ((index, longitude) in listOf(.009, .010, .011).withIndex()) {
            val forecast = update(estimator, route, binding(shape),
                LocationFix(50.0057, longitude, 10.0, base + 80_000 + index * 5_000, 12.0))
            if (index < 2) assertNull("The old parallel-road lock must not supply the new path's window", forecast)
            else assertNotNull("Independent fresh movement can establish the newly supported road", forecast)
        }
    }

    @Test
    fun aProvenRoadCannotRetainAForecastWhenANearerAlternativeBecomesInternallyAmbiguous() {
        val route = simpleRoute()
        val main = curvedPath(.005)
        val loop = listOf(RoutePoint(50.0, 0.0), RoutePoint(50.0057, 0.0),
            RoutePoint(50.0057, .010), RoutePoint(50.0077, .010), RoutePoint(50.0077, .013),
            RoutePoint(50.0057, .013), RoutePoint(50.0057, .009), RoutePoint(50.0057, .02),
            RoutePoint(50.0, .02))
        val shape = RoadRouteGeometry(point(route[0]), point(route[1]), listOf(main, loop), base)
        val estimator = GpsJourneyTimeEstimator()
        for ((index, longitude) in listOf(.004, .006, .008).withIndex()) {
            val forecast = update(estimator, route, binding(shape),
                LocationFix(50.005, longitude, 10.0, base + 60_000 + index * 5_000, 12.0))
            if (index == 2) assertNotNull("The initial main road must have an independent unique lock", forecast)
        }
        val fix = LocationFix(50.0057, .010, 10.0, base + 80_000, 12.0)
        val prepared = TrackingRouteGeometry.prepare(shape, route[0], route[1], binding(shape).source, base)!!
        val mainProjection = TrackingRouteGeometry.project(prepared.paths.first(), fix)
        assertNotNull(mainProjection.projection)
        assertTrue(mainProjection.projection!!.across in 70.0..85.0)
        val loopProjection = TrackingRouteGeometry.project(prepared.paths.last(), fix)
        assertTrue(loopProjection.ambiguous)
        assertNull(loopProjection.projection)
        assertNull("A fresh loop ambiguity must invalidate even an unexpired locked-road forecast",
            update(estimator, route, binding(shape), fix))
        assertEquals(GpsTimeUnavailableReason.AMBIGUOUS_ROUTE, estimator.unavailableReason())
    }

    @Test
    fun aWithinPathLoopRemainsAmbiguousEvenWhenAnotherRoadIsSupported() {
        val route = simpleRoute()
        val crossing = listOf(RoutePoint(50.0, 0.0), RoutePoint(50.004, .02),
            RoutePoint(50.004, 0.0), RoutePoint(50.0, .02))
        val straight = listOf(RoutePoint(50.0, 0.0), RoutePoint(50.002, .01), RoutePoint(50.0, .02))
        val shape = RoadRouteGeometry(point(route[0]), point(route[1]), listOf(crossing, straight), base)
        val estimator = GpsJourneyTimeEstimator()
        assertNull(update(estimator, route, binding(shape), LocationFix(50.002, .01, 10.0, base + 60_000, 12.0)))
        assertEquals(GpsTimeUnavailableReason.AMBIGUOUS_ROUTE, estimator.unavailableReason())
    }

    private fun publicGeometry(): RoadRouteGeometry {
        val json = javaClass.getResourceAsStream("/routing/muelheim-essen-osrm.json")!!
            .bufferedReader().use { it.readText() }
        return RoadRouteParser.parse(json, from, to, base)!!
    }

    private fun publicRoute(travelMillis: Long = travel) = listOf(
        TrackingStop("from", 1, "Mülheim", from.latitude, from.longitude,
            null, null, base, isOrigin = true, plannedDepartureMillis = base),
        TrackingStop("to", 2, "Essen", to.latitude, to.longitude,
            base + travelMillis, base + travelMillis, null, isDestination = true)
    )

    private fun publicFixes(geometry: RoadRouteGeometry): List<LocationFix> = listOf(205, 206, 207).mapIndexed { i, vertex ->
        val point = geometry.alternatives.first()[vertex]
        LocationFix(point.latitude, point.longitude, 10.0, base + 900_000 + i * 10_000, 12.0)
    }

    private fun establishPublic(estimator: GpsJourneyTimeEstimator, geometry: RoadRouteGeometry): GpsJourneyTimes =
        publicFixes(geometry).map { update(estimator, publicRoute(), binding(geometry), it) }.last()!!

    private fun update(estimator: GpsJourneyTimeEstimator, route: List<TrackingStop>, segment: GpsSegmentGeometry,
                       fix: LocationFix, now: Long = fix.timeMillis): GpsJourneyTimes? =
        estimator.update(route, TrackingProgress(nextIndex = 1, nextStopKey = "to", gpsEstablished = true),
            TrackingSource.GPS, fix, now, listOf(segment), useRoadGeometry = true)

    private fun binding(geometry: RoadRouteGeometry) = GpsSegmentGeometry("from", "to", geometry)
    private fun point(stop: TrackingStop) = RoutePoint(stop.latitude!!, stop.longitude!!)
    private fun simpleRoute() = listOf(
        TrackingStop("from", 1, "Origin", 50.0, 0.0, null, null, base,
            isOrigin = true, plannedDepartureMillis = base),
        TrackingStop("to", 2, "Target", 50.0, .02, base + 120_000, base + 120_000, null,
            isDestination = true)
    )
    private fun curvedPath(latitudeOffset: Double) = listOf(RoutePoint(50.0, 0.0),
        RoutePoint(50.0 + latitudeOffset, 0.0), RoutePoint(50.0 + latitudeOffset, .02), RoutePoint(50.0, .02))
}
