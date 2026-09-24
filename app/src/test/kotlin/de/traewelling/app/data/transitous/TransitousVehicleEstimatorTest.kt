package de.traewelling.app.data.transitous

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitousVehicleEstimatorTest {
    private val bounds = TransitousBounds(38.0, -127.0, 44.0, -119.0)
    private val departure = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun decodesGooglePolylineAndRejectsTruncation() {
        val path = TransitousPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, path.size)
        assertEquals(38.5, path.first().latitude, 0.00001)
        assertEquals(-120.2, path.first().longitude, 0.00001)
        assertEquals(43.252, path.last().latitude, 0.00001)
        assertTrue(TransitousPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq", 5).isEmpty())
    }

    @Test
    fun interpolatesByDistanceRatherThanByVertexIndex() {
        val path = listOf(
            TransitousCoordinate(0.0, 0.0),
            TransitousCoordinate(0.0, 0.01),
            TransitousCoordinate(0.0, 0.03),
        )
        assertEquals(0.015, TransitousPolyline.atFraction(path, 0.5).longitude, 0.000001)
    }

    @Test
    fun estimatesOnlyCurrentSegmentsAndKeepsScheduledTripsDistinct() {
        val segment = TransitousTripSegmentDto(
            trips = listOf(TransitousTripInfoDto("feed:trip1", "ICE 123")),
            mode = "LONG_DISTANCE",
            routeColor = "#FF0000",
            from = TransitousPlaceDto("Start", 38.5, -120.2),
            to = TransitousPlaceDto("End", 40.7, -120.95),
            departure = "2026-09-24T12:00:00Z",
            arrival = "2026-09-24T12:10:00Z",
            scheduledDeparture = "2026-09-24T11:57:00Z",
            scheduledArrival = "2026-09-24T12:07:00Z",
            realTime = true,
            polyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
        )
        assertTrue(TransitousVehicleEstimator.estimate(listOf(segment), departure.minusSeconds(1), bounds, 5, 10).isEmpty())
        val markers = TransitousVehicleEstimator.estimate(
            listOf(segment, segment.copy(realTime = false)), departure.plusSeconds(300), bounds, 5, 10
        )
        assertEquals(1, markers.size) // same trip can appear in more than one segment
        assertEquals("ICE 123", markers.single().displayName)
        assertEquals(3, markers.single().delayMinutes)
        assertTrue(markers.single().realTime)
        assertTrue(bounds.contains(markers.single().position))
        assertTrue(TransitousVehicleEstimator.estimate(listOf(segment), departure.plusSeconds(600), bounds, 5, 10).isEmpty())

        val scheduled = TransitousVehicleEstimator.estimate(
            listOf(segment.copy(realTime = false)), departure.plusSeconds(300), bounds, 5, 10
        ).single()
        assertFalse(scheduled.realTime)
        assertEquals(null, scheduled.delayMinutes)
    }

    @Test
    fun limitsWideViewportToOneDegreeAroundItsCenter() {
        val visible = TransitousBounds(46.0, 7.0, 54.0, 15.0)
        assertEquals(TransitousBounds(49.5, 10.5, 50.5, 11.5), visible.limited())
    }
}
