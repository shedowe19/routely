package de.traewelling.app.service

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RideDiscoveryRequestsTest {
    @Test fun allDepartureLookupsFailInsteadOfReportingAnEmptySearch() {
        val requests = RideDiscoveryRequests()
        val firstFailure = IOException("Departure lookup unavailable")
        assertNull(requests.valueOrNull(Result.failure<List<String>>(firstFailure)))
        assertNull(requests.valueOrNull(Result.failure<List<String>>(IOException("Second station unavailable"))))

        assertSame(firstFailure, assertThrows(IOException::class.java) { requests.throwIfAllFailed() })
    }

    @Test fun validEmptyDepartureResponseIsStillASuccess() {
        val requests = RideDiscoveryRequests()
        requests.valueOrNull(Result.failure<List<String>>(IOException("First station unavailable")))
        assertEquals(emptyList<String>(), requests.valueOrNull(Result.success(emptyList<String>())))
        requests.throwIfAllFailed()
    }

    @Test fun partialDepartureResultsSurviveFailuresBeforeAndAfterSuccess() {
        val requests = RideDiscoveryRequests()
        val results = listOf(
            Result.failure<List<String>>(IOException("First station unavailable")),
            Result.success(listOf("ride-a", "ride-b")),
            Result.failure<List<String>>(IOException("Last station unavailable"))
        ).flatMap { requests.valueOrNull(it).orEmpty() }
        requests.throwIfAllFailed()

        assertEquals(listOf("ride-a", "ride-b"), results)
    }

    @Test fun allTripDetailLookupsFailInsteadOfReportingNoMatchingRide() {
        val requests = RideDiscoveryRequests()
        val firstFailure = IOException("Trip details unavailable")
        val details = listOf(
            Result.failure<String>(firstFailure),
            Result.failure<String>(IOException("Other trip unavailable"))
        ).mapNotNull { requests.valueOrNull(it) }

        assertEquals(emptyList<String>(), details)
        assertSame(firstFailure, assertThrows(IOException::class.java) { requests.throwIfAllFailed() })
    }

    @Test fun partialTripDetailsSurviveAnUnavailableTrip() {
        val requests = RideDiscoveryRequests()
        val details = listOf(
            Result.failure<String>(IOException("First trip unavailable")),
            Result.success("usable-trip-details")
        ).mapNotNull { requests.valueOrNull(it) }
        requests.throwIfAllFailed()

        assertEquals(listOf("usable-trip-details"), details)
    }

    @Test fun usableCachedTripDetailsAllowOtherTripRequestsToFail() {
        val requests = RideDiscoveryRequests()
        assertEquals("cached-trip-details", requests.valueOrNull(Result.success("cached-trip-details")))
        requests.valueOrNull(Result.failure<String>(IOException("Uncached trip unavailable")))
        requests.throwIfAllFailed()
    }

    @Test fun noNearbyStationOrMatchingDepartureNeedsNoSuccessfulRequest() {
        RideDiscoveryRequests().throwIfAllFailed()
    }

    @Test fun wrappedCancellationStopsDiscoveryImmediatelyAfterPartialSuccess() {
        val requests = RideDiscoveryRequests()
        requests.valueOrNull(Result.success(listOf("ride-a")))
        val cancelled = CancellationException("Search session stopped")

        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            requests.valueOrNull(Result.failure<List<String>>(cancelled))
        })
    }
}
