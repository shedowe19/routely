package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.StopRealtimeInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.service.JourneyTime
import de.traewelling.app.service.JourneyTimeSource
import org.junit.Assert.*
import org.junit.Test

class RealtimePresentationTest {
    private val now = 1_000_000L
    private val iris = StopRealtimeInfo(now - 5_000L, "DBF · IRIS")
    private val traewelling = StopRealtimeInfo(now - 30_000L, "Träwelling")

    @Test fun retrievalTimestampDoesNotPromiseProviderFreshness() {
        val recent = realtimeRetrievalPresentation(iris.copy(providerUpdatedAtMillis = 1L), now)
        assertTrue(recent.recentlyRetrieved)
        assertEquals("Daten abgerufen", recent.label)
        assertEquals("DBF · IRIS · abgerufen vor 5 s", recent.detail)
        assertFalse(recent.label.contains("live", ignoreCase = true))
    }

    @Test fun missingFutureAndOldReadTimesAreUnconfirmedOrStale() {
        assertFalse(realtimeRetrievalPresentation(null, now).recentlyRetrieved)
        assertEquals("Abruf nicht bestätigt", realtimeRetrievalPresentation(null, now).label)
        assertFalse(realtimeRetrievalPresentation(iris.copy(fetchedAtMillis = 0L), now).recentlyRetrieved)
        assertFalse(realtimeRetrievalPresentation(iris.copy(fetchedAtMillis = now + 1L), now).recentlyRetrieved)
        assertEquals("Abruf veraltet",
            realtimeRetrievalPresentation(iris.copy(fetchedAtMillis = now - 120_001L), now).label)
        assertTrue(realtimeRetrievalPresentation(iris.copy(fetchedAtMillis = now - 120_000L), now).recentlyRetrieved)
    }

    @Test fun failedRefreshDoesNotRelabelRetainedReadsAsFresh() {
        val result = realtimeRetrievalPresentation(iris, now, refreshFailed = true)
        assertFalse(result.recentlyRetrieved)
        assertEquals("Offline / Abruffehler", result.label)
        assertTrue(result.detail.endsWith("abgerufen vor 5 s"))
    }

    @Test fun platformsCompareTheSelectedEventWithItsOwnPlan() {
        val stop = StopStation(arrivalPlatformPlanned = "5", arrivalPlatformReal = "7",
            departurePlatformPlanned = "1", departurePlatformReal = "2")
        assertEquals(PlatformPresentation("Gleis 7 statt 5", true), platformPresentation(stop, StopEvent.ARRIVAL))
        assertEquals(PlatformPresentation("Gleis 2 statt 1", true), platformPresentation(stop, StopEvent.DEPARTURE))
    }

    @Test fun missingArrivalCannotBorrowTheDeparturePlatform() {
        val stop = StopStation(departurePlatformPlanned = "1", departurePlatformReal = "2")
        assertNull(platformPresentation(stop, StopEvent.ARRIVAL))
        assertEquals("Gleis 2 statt 1", platformPresentation(stop, StopEvent.DEPARTURE)?.label)
    }

    @Test fun platformPrefixesAndWhitespaceDoNotFabricateChanges() {
        val stop = StopStation(arrivalPlatformPlanned = " Gl. 05 A ", arrivalPlatformReal = "Gleis 05 A")
        assertEquals(PlatformPresentation("Gleis 05 A", false), platformPresentation(stop, StopEvent.ARRIVAL))
        assertEquals("Gleis 05 A", platformPresentation(stop.copy(arrivalPlatformReal = " "), StopEvent.ARRIVAL)?.label)
        assertEquals("Gleis 4 Süd", platformPresentation(StopStation(platform = "4 Süd"), StopEvent.DEPARTURE)?.label)
    }

    @Test fun eventAndPlatformProvenanceStayIndependent() {
        val stop = StopStation(arrivalRealtimeInfo = traewelling, departureRealtimeInfo = iris,
            arrivalPlatformRealtimeInfo = iris, departurePlatformRealtimeInfo = traewelling)
        assertSame(traewelling, eventRealtimeInfo(stop, StopEvent.ARRIVAL))
        assertSame(iris, eventRealtimeInfo(stop, StopEvent.DEPARTURE))
        assertSame(iris, eventPlatformRealtimeInfo(stop, StopEvent.ARRIVAL))
        assertSame(traewelling, eventPlatformRealtimeInfo(stop, StopEvent.DEPARTURE))
    }

    @Test fun freshDepartureDoesNotHideAnOldArrival() {
        val stop = StopStation(arrivalRealtimeInfo = traewelling.copy(fetchedAtMillis = now - 120_001L),
            departureRealtimeInfo = iris)
        val summary = stopoversRetrievalPresentation(listOf(stop), now, false)
        assertFalse(summary.recentlyRetrieved)
        assertEquals("Abruf veraltet", summary.label)
        assertTrue(summary.detail.startsWith("Träwelling + DBF · IRIS"))
    }

    @Test fun stalePlatformReadIsIncludedInTheSummary() {
        val stop = StopStation(arrivalRealtimeInfo = iris, departureRealtimeInfo = iris,
            arrivalPlatformRealtimeInfo = traewelling.copy(fetchedAtMillis = now - 130_000L))
        assertEquals("Abruf veraltet", stopoversRetrievalPresentation(listOf(stop), now, false).label)
    }

    @Test fun unknownArrivalMetadataCannotBeHiddenByAFreshDeparture() {
        val stop = StopStation(arrivalPlanned = "2026-10-10T12:00:00Z", departureRealtimeInfo = iris)
        assertEquals("Abruf nicht bestätigt", stopoversRetrievalPresentation(listOf(stop), now, false).label)
    }

    @Test fun anyFutureTimestampMakesTheSummaryUnconfirmed() {
        val stop = StopStation(arrivalRealtimeInfo = traewelling,
            departureRealtimeInfo = iris.copy(fetchedAtMillis = now + 1L))
        assertEquals("Abruf nicht bestätigt", stopoversRetrievalPresentation(listOf(stop), now, false).label)
    }

    @Test fun combinedSourceLabelsDoNotDuplicateTheirSeparatorSuffix() {
        val stop = StopStation(arrivalRealtimeInfo = iris, departureRealtimeInfo = iris)
        assertEquals("DBF · IRIS · abgerufen vor 5 s",
            stopoversRetrievalPresentation(listOf(stop), now, false).detail)
    }

    @Test fun gpsAndManualDisplaySourcesNeverAcquireProviderProvenance() {
        for (source in listOf(JourneyTimeSource.GPS_OBSERVED, JourneyTimeSource.GPS_ESTIMATE,
            JourneyTimeSource.MANUAL, JourneyTimeSource.TIMETABLE)) {
            val time = JourneyTime(now, source, now)
            assertEquals(time.sourceLabel, journeyTimeSourceLabel(time, iris, now, false))
        }
        val apiTime = JourneyTime(now, JourneyTimeSource.API_REALTIME, now)
        assertTrue(journeyTimeSourceLabel(apiTime, iris, now, false).startsWith("DBF · IRIS"))
        assertTrue(journeyTimeSourceLabel(apiTime, iris, now, true).contains("abruffehler"))
    }
}
