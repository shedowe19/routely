package de.traewelling.app.service

import com.google.gson.JsonParser
import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.data.model.TransitRouteVisit
import de.traewelling.app.data.routing.TransitRouteParser
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

class NativeRailGpsTest {
    @Test fun publicIceGeometryFlowsThroughOrderedTrackingIntoGpsTimesOnARealCurve() {
        val stops = JsonParser.parseString(resource("public-ice-929-stops.json")).asJsonObject["data"].asJsonArray
        val visits = stops.map { raw ->
            val stop = raw.asJsonObject
            val station = stop["station"].asJsonObject
            TransitRouteVisit(stop["uuid"].asString, station["id"].asInt, station["uuid"].asString,
                Instant.parse(stop["arrivalPlanned"].asString).toEpochMilli(),
                Instant.parse(stop["departurePlanned"].asString).toEpochMilli(),
                RoutePoint(station["latitude"].asDouble, station["longitude"].asDouble))
        }
        val request = TransitRouteRequest(9258278, "8975982", visits)
        val departure = visits.first().departurePlannedMillis!!
        val native = resource("public-ice-929-polyline.json")
        val geometry = TransitRouteParser.parse(native, request, departure)!!
        val first = geometry.segments.single { it.fromKey == visits.first().key && it.toKey == visits[1].key }
        val route = visits.mapIndexed { index, visit -> TrackingStop(visit.key, visit.stationId, visit.key,
            visit.point.latitude, visit.point.longitude, visit.arrivalPlannedMillis,
            visit.arrivalPlannedMillis, visit.departurePlannedMillis, isOrigin = index == 0,
            isDestination = index == visits.lastIndex, plannedDepartureMillis = visit.departurePlannedMillis) }
        val engine = StationTrackingEngine(route, TrackingProgress(nextIndex = 1,
            nextStopKey = visits[1].key, gpsEstablished = true))
        engine.updateSegmentGeometries(geometry.segments)
        val estimator = GpsJourneyTimeEstimator()
        val coordinates = JsonParser.parseString(native).asJsonObject["data"].asJsonObject["features"].asJsonArray[0]
            .asJsonObject["geometry"].asJsonObject["coordinates"].asJsonArray
        val points = listOf(first.geometry.from) + first.geometry.alternatives.single() + first.geometry.to
        val total = points.zipWithNext().sumOf { (a, b) -> sphericalDistance(a, b) }
        val duration = visits[1].arrivalPlannedMillis!! - departure
        var times: GpsJourneyTimes? = null
        // These public vertices are over one kilometre away from the station chord.
        for (index in listOf(75, 82, 87)) {
            val pair = coordinates[index].asJsonArray
            val point = RoutePoint(pair[1].asDouble, pair[0].asDouble)
            val position = points.indexOf(point)
            assertTrue(position >= 0)
            val along = points.take(position + 1).zipWithNext().sumOf { (a, b) -> sphericalDistance(a, b) }
            val fix = LocationFix(point.latitude, point.longitude, 10.0,
                departure + 90_000 + (duration * along / total).roundToLong(), 25.0)
            val update = engine.onLocation(fix, fix.timeMillis)
            assertEquals(visits[1].key, update.stop!!.key)
            assertFalse(update.destinationReached)
            assertNull(GpsJourneyTimeEstimator().update(route, engine.getProgress(), update.source, fix, fix.timeMillis))
            times = estimator.update(route, engine.getProgress(), update.source, fix, fix.timeMillis, geometry.segments)
        }
        val forecast = times!!.stopTimes.first { it.stopKey == visits[1].key }
        assertTrue(kotlin.math.abs(forecast.arrivalMillis!! - (visits[1].arrivalPlannedMillis!! + 90_000)) < 1_000)
        assertFalse(forecast.arrivalObserved)
        assertEquals(GpsGeometrySource.TRIP_POLYLINE, estimator.geometrySource())
        assertEquals(GpsGeometrySource.TRIP_POLYLINE, engine.geometrySource())
        assertNull(estimator.unavailableReason())
        assertEquals(emptySet<String>(), engine.getProgress().announcedKeys)
        assertEquals(visits[1].arrivalPlannedMillis, route[1].effectiveArrivalMillis)
    }

    private fun resource(name: String) = javaClass.getResourceAsStream("/routing/transit/$name")!!
        .bufferedReader().use { it.readText() }

    /** Independent spherical distances for the synthetic timing of public route vertices. */
    private fun sphericalDistance(a: RoutePoint, b: RoutePoint): Double {
        val latitude = Math.toRadians(b.latitude - a.latitude)
        val longitude = Math.toRadians(b.longitude - a.longitude)
        val h = sin(latitude / 2) * sin(latitude / 2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(longitude / 2) * sin(longitude / 2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
