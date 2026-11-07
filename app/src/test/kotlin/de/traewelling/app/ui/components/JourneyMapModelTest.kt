package de.traewelling.app.ui.components

import de.traewelling.app.data.model.GpsGeometrySource
import de.traewelling.app.data.model.GpsSegmentGeometry
import de.traewelling.app.data.model.RouteGeometry
import de.traewelling.app.data.model.RoutePoint
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.data.model.TransitRouteGeometry
import de.traewelling.app.data.model.TransitRouteRequest
import de.traewelling.app.data.model.TransitRouteVisit
import de.traewelling.app.ui.screens.StopTimelineProgress
import de.traewelling.app.ui.screens.TimelinePositionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JourneyMapModelTest {
    private val start = Instant.parse("2026-11-07T10:00:00Z")
    private val end = Instant.parse("2026-11-07T11:00:00Z")

    @Test fun interpolatesTimetablePositionBetweenStops() {
        val model = buildJourneyMapModel(
            stops = listOf(stop("A", 50.0, 8.0, start, start), stop("B", 50.0, 9.0, end, end)),
            progress = StopTimelineProgress(),
            nowMillis = start.plusSeconds(1_800).toEpochMilli(),
            originIndex = 0,
            destinationIndex = 1,
            geometry = null,
        )!!
        assertEquals("Unterwegs nach B", model.position!!.label)
        assertEquals(50.0, model.position!!.latitude, 0.0001)
        assertEquals(8.5, model.position!!.longitude, 0.0001)
        assertFalse(model.detailedRoute)
    }

    @Test fun dwellAtStopAndStartAndDestinationLabels() {
        val stops = listOf(
            stop("A", 50.0, 8.0, start, start.plusSeconds(60)),
            stop("B", 51.0, 9.0, end, end),
        )
        val dwell = buildJourneyMapModel(stops, StopTimelineProgress(),
            start.plusSeconds(30).toEpochMilli(), 0, 1, null)!!
        assertEquals("Halt: A", dwell.position!!.label)
        assertEquals(50.0, dwell.position!!.latitude, 0.0001)

        val before = buildJourneyMapModel(stops, StopTimelineProgress(),
            start.minusSeconds(60).toEpochMilli(), 0, 1, null)!!
        assertEquals("Start: A", before.position!!.label)

        val after = buildJourneyMapModel(stops, StopTimelineProgress(),
            end.plusSeconds(60).toEpochMilli(), 0, 1, null)!!
        assertEquals("Ziel: B", after.position!!.label)
    }

    @Test fun gpsProgressOverridesClockEstimate() {
        val stops = listOf(
            stop("A", 50.0, 8.0, start, start),
            stop("B", 50.0, 9.0, end, end),
        )
        val current = buildJourneyMapModel(
            stops, StopTimelineProgress(currentIndex = 0, source = TimelinePositionSource.GPS),
            start.plusSeconds(1_800).toEpochMilli(), 0, 1, null
        )!!
        assertEquals("Halt: A", current.position!!.label)
        assertEquals(50.0, current.position!!.latitude, 0.0001)

        val next = buildJourneyMapModel(
            stops, StopTimelineProgress(nextIndex = 1, source = TimelinePositionSource.GPS),
            start.plusSeconds(1_800).toEpochMilli(), 0, 1, null
        )!!
        assertEquals("Unterwegs nach B", next.position!!.label)
        assertEquals(8.5, next.position!!.longitude, 0.0001)

        val completed = buildJourneyMapModel(
            stops, StopTimelineProgress(passedThroughIndex = 1, source = TimelinePositionSource.GPS),
            end.plusSeconds(60).toEpochMilli(), 0, 1, null
        )!!
        assertEquals("Ziel: B", completed.position!!.label)
    }

    @Test fun usesDetailedRouteAndSnapsMarkerToIt() {
        val geometry = geometryWithCurve()
        val model = buildJourneyMapModel(
            stops = listOf(stop("A", 50.0, 8.0, start, start), stop("B", 50.0, 9.0, end, end)),
            progress = StopTimelineProgress(),
            nowMillis = start.plusSeconds(1_800).toEpochMilli(),
            originIndex = 0,
            destinationIndex = 1,
            geometry = geometry,
        )!!
        assertTrue(model.detailedRoute)
        assertEquals(3, model.route.size)
        assertTrue("marker should snap to the curved track, was ${model.position}", model.position!!.latitude > 50.05)
    }

    @Test fun refusesMissingCoordinatesOrIncompleteRange() {
        val onlyOne = listOf(stop("A", 50.0, 8.0, start, start))
        assertNull(buildJourneyMapModel(onlyOne, StopTimelineProgress(), start.toEpochMilli(), 0, 0, null))
        val missing = listOf(
            StopStation(station = TrainStation(name = "A")),
            stop("B", 50.0, 9.0, end, end),
        )
        assertNull(buildJourneyMapModel(missing, StopTimelineProgress(), start.toEpochMilli(), 0, 1, null))
        assertNull(buildJourneyMapModel(onlyOne, StopTimelineProgress(), start.toEpochMilli(), -1, 0, null))
    }

    @Test fun waitingGpsHasNoMarkerButKeepsRoute() {
        val model = buildJourneyMapModel(
            listOf(stop("A", 50.0, 8.0, start, start), stop("B", 50.0, 9.0, end, end)),
            StopTimelineProgress(source = TimelinePositionSource.WAITING),
            start.toEpochMilli(), 0, 1, null
        )!!
        assertNull(model.position)
        assertEquals(2, model.route.size)
        assertNotNull(model.stops.firstOrNull())
    }

    private fun stop(
        name: String,
        latitude: Double,
        longitude: Double,
        arrival: Instant,
        departure: Instant,
    ) = StopStation(
        station = TrainStation(name = name, latitude = latitude, longitude = longitude),
        arrivalPlanned = arrival.toString(),
        departurePlanned = departure.toString(),
    )

    private fun geometryWithCurve(): TransitRouteGeometry {
        val request = TransitRouteRequest(
            statusId = 1,
            tripIdentity = "1:uuid",
            visits = listOf(
                TransitRouteVisit("a", null, null, null, null, RoutePoint(50.0, 8.0)),
                TransitRouteVisit("b", null, null, null, null, RoutePoint(50.0, 9.0)),
            ),
        )
        val path = listOf(RoutePoint(50.0, 8.0), RoutePoint(51.0, 8.5), RoutePoint(50.0, 9.0))
        return TransitRouteGeometry(
            request = request,
            segments = listOf(
                GpsSegmentGeometry(
                    fromKey = "a",
                    toKey = "b",
                    geometry = RouteGeometry(
                        from = RoutePoint(50.0, 8.0),
                        to = RoutePoint(50.0, 9.0),
                        alternatives = listOf(path),
                        fetchedAtMillis = 1L,
                    ),
                    source = GpsGeometrySource.TRIP_POLYLINE,
                )
            ),
            fetchedAtMillis = 1L,
        )
    }
}
