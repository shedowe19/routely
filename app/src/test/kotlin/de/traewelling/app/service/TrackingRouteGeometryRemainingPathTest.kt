package de.traewelling.app.service

import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.routing.RoadRouteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared future geometry must be physical and ordered, independently of a past detour. */
class TrackingRouteGeometryRemainingPathTest {
    @Test
    fun publicMuelheimEssenAlternativesShareTheFutureRoadDespiteDifferentPastDistances() {
        val from = RoutePoint(51.43175246, 6.88538831)
        val to = RoutePoint(51.45018831, 7.0101172)
        val json = javaClass.getResourceAsStream("/routing/muelheim-essen-osrm.json")!!
            .bufferedReader().use { it.readText() }
        val geometry = RoadRouteParser.parse(json, from, to, 1_000L)!!
        val segment = TrackingRouteGeometry.prepare(geometry, stop("from", from), stop("to", to),
            GpsGeometrySource.ROAD_MODEL, 1_000L)!!
        // This is a published route vertex, never a device position.
        val point = geometry.alternatives.first()[205]
        val projections = segment.paths.map { path ->
            TrackingRouteGeometry.project(path, LocationFix(point.latitude, point.longitude, 10.0, 1_000L))
                .projection!!
        }
        assertTrue(kotlin.math.abs(projections[0].length - projections[1].length) > 800.0)
        val remaining = projections.map { (1.0 - it.fraction) * it.length }
        assertEquals(remaining[0], remaining[1], .001)
        assertTrue(TrackingRouteGeometry.sharedRemainingPath(projections))
        assertTrue(TrackingRouteGeometry.sharedRemainingPath(projections.reversed()))
    }

    @Test
    fun collinearDensificationDoesNotInventADifferentFutureRoad() {
        val sparse = path(point(0.0, 0.0), point(0.0, .010), point(.005, .010), point(.005, .020))
        val dense = path(point(0.0, 0.0), point(0.0, .005), point(0.0, .010),
            point(.002, .010), point(.005, .010), point(.005, .013), point(.005, .019), point(.005, .020))
        val fix = LocationFix(50.0, .006, 10.0, 1_000L)
        val projections = listOf(sparse, dense).map { TrackingRouteGeometry.project(it, fix).projection!! }
        assertTrue(TrackingRouteGeometry.sharedRemainingPath(projections))
        assertTrue(TrackingRouteGeometry.sharedRemainingPath(projections.reversed()))
    }

    @Test
    fun comparisonStartsAtTheProjectedPositionInsideAnEdge() {
        val common = point(.004, .004)
        val end = point(.004, .020)
        val north = path(point(0.0, 0.0), point(.004, 0.0), common, end)
        val south = path(point(0.0, 0.0), point(-.006, 0.0), point(-.006, .004), common, end)
        val fix = LocationFix(50.004, .011, 10.0, 1_000L)
        val projections = listOf(north, south).map { TrackingRouteGeometry.project(it, fix).projection!! }
        assertTrue(TrackingRouteGeometry.sharedRemainingPath(projections))
        assertFalse(TrackingRouteGeometry.sharedRemainingPath(listOf(atStart(north), atStart(south))))
    }

    @Test
    fun sameEndpointAndSimilarRemainingLengthDoNotProveTheFutureRoad() {
        val start = point(0.0, 0.0)
        val split = point(0.0, .005)
        val end = point(0.0, .015)
        val north = path(start, split, point(.002, .010), end)
        val south = path(start, split, point(-.002, .010), end)
        assertEquals(north.length, south.length, .1)
        assertFalse(TrackingRouteGeometry.sharedRemainingPath(listOf(atStart(north), atStart(south))))
    }

    @Test
    fun equalVertexSetsInDifferentVisitOrderDoNotProveASharedFuture() {
        val start = point(0.0, 0.0)
        val north = point(.002, .004)
        val south = point(-.002, .006)
        val end = point(0.0, .010)
        val first = path(start, north, south, end)
        val second = path(start, south, north, end)
        assertFalse(TrackingRouteGeometry.sharedRemainingPath(listOf(atStart(first), atStart(second))))
    }

    @Test
    fun aFutureLoopCannotHideBehindTheSameFinalRoad() {
        val start = point(0.0, 0.0)
        val end = point(0.0, .020)
        val direct = path(start, end)
        val loop = path(start, point(.003, 0.0), point(.003, .005), start, end)
        assertFalse(TrackingRouteGeometry.sharedRemainingPath(listOf(atStart(direct), atStart(loop))))
    }

    @Test
    fun nearParallelRoadsRemainDifferentEvenWhenTheirEndpointsMatch() {
        val start = point(0.0, 0.0)
        val end = point(0.0, .020)
        val direct = path(start, end)
        val parallel = path(start, point(.00003, .004), point(.00003, .016), end)
        assertFalse(TrackingRouteGeometry.sharedRemainingPath(listOf(atStart(direct), atStart(parallel))))
    }

    @Test
    fun aThirdDifferentAlternativeCannotBeIgnoredByAReferenceOnlyComparison() {
        val direct = path(point(0.0, 0.0), point(0.0, .020))
        val north = path(point(.000008, 0.0), point(.000008, .020))
        val south = path(point(-.000008, 0.0), point(-.000008, .020))
        // Each is within one metre of the first road; the pair differs by more than a metre.
        assertTrue(TrackingRouteGeometry.sharedRemainingPath(listOf(atStart(direct), atStart(north))))
        assertTrue(TrackingRouteGeometry.sharedRemainingPath(listOf(atStart(direct), atStart(south))))
        assertFalse(TrackingRouteGeometry.sharedRemainingPath(listOf(atStart(direct), atStart(north), atStart(south))))
    }

    @Test
    fun anEndpointHasNoRemainingShapeToProve() {
        val road = path(point(0.0, 0.0), point(0.0, .020))
        val arrival = TrackingRouteGeometry.Projection(1.0, road.length, road, 0.0)
        assertFalse(TrackingRouteGeometry.sharedRemainingPath(listOf(arrival, arrival)))
    }

    private fun point(latitude: Double, longitude: Double) = RoutePoint(50.0 + latitude, longitude)

    private fun path(vararg points: RoutePoint): TrackingRouteGeometry.Path {
        val lengths = points.toList().zipWithNext { from, to -> TrackingRouteGeometry.distance(from, to) }
        return TrackingRouteGeometry.Path(points.toList(), lengths, lengths.sum())
    }

    private fun atStart(path: TrackingRouteGeometry.Path) = TrackingRouteGeometry.Projection(0.0, path.length, path, 0.0)

    private fun stop(key: String, point: RoutePoint) = TrackingStop(
        key = key, stationId = null, name = key, latitude = point.latitude, longitude = point.longitude,
        effectiveArrivalMillis = null, plannedArrivalMillis = null, effectiveDepartureMillis = null
    )
}
