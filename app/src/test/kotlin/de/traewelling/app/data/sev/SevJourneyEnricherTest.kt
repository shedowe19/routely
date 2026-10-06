package de.traewelling.app.data.sev

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SevJourneyEnricherTest {
    private val first = stop("first", 1, "Essen Hbf")
    private val middle = stop("middle", 2, "Mülheim (Ruhr) Hbf")
    private val last = stop("last", 3, "Duisburg Hbf")

    @Test fun requestsOnlyUniqueStationsWithinTheUniquelyBoundCheckin() {
        val before = stop("before", 4, "Düsseldorf Hbf")
        val after = stop("after", 5, "Oberhausen Hbf")
        val repeatedMiddle = middle.copy(uuid = "middle-return", departurePlanned = "2026-10-06T10:10:00Z")
        assertEquals(listOf("essen-hbf", "muelheim-ruhr-hbf", "duisburg-hbf"),
            SevJourneyEnricher.stationSlugs(checkin(), listOf(before, first, middle, repeatedMiddle, last, after)))
    }

    @Test fun duplicateOriginOrDestinationVisitDoesNotChooseTheFirstNetworkWindow() {
        for (route in listOf(listOf(first, first, middle, last), listOf(first, middle, last, last))) {
            assertTrue(SevJourneyEnricher.stationSlugs(checkin(), route).isEmpty())
        }
    }

    @Test fun ambiguousPartialTimeIdentityWithoutUuidDoesNotChooseAWindow() {
        val selected = first.copy(uuid = null)
        val another = selected.copy(arrivalPlanned = "2026-10-06T10:05:00Z")
        assertTrue(SevJourneyEnricher.stationSlugs(checkin().copy(origin = selected),
            listOf(selected, another, middle, last)).isEmpty())
    }

    @Test fun uniquePlannedTimeFallbackStillBindsARepeatedStationVisit() {
        val selected = first.copy(uuid = null)
        val earlier = selected.copy(departurePlanned = "2026-10-06T09:00:00Z")
        assertEquals(listOf("essen-hbf", "muelheim-ruhr-hbf", "duisburg-hbf"),
            SevJourneyEnricher.stationSlugs(checkin().copy(origin = selected),
                listOf(earlier, selected, middle, last)))
    }

    @Test fun missingReversedAndNonReplacementCheckinsHaveNoPublicRequests() {
        val route = listOf(first, middle, last)
        assertTrue(SevJourneyEnricher.stationSlugs(checkin().copy(origin = first.copy(uuid = "missing")), route).isEmpty())
        assertTrue(SevJourneyEnricher.stationSlugs(checkin().copy(origin = last, destination = first), route).isEmpty())
        assertTrue(SevJourneyEnricher.stationSlugs(checkin().copy(category = "regional"), route).isEmpty())
    }

    @Test fun cancelledStationsAreExcludedAndPublicRequestBatchStaysBounded() {
        assertEquals(listOf("essen-hbf", "duisburg-hbf"),
            SevJourneyEnricher.stationSlugs(checkin(), listOf(first, middle.copy(cancelled = true), last)))
        val stations = (1..70).map { stop("visit-$it", it, "Station $it") }
        val slugs = SevJourneyEnricher.stationSlugs(checkin().copy(origin = stations.first(), destination = stations.last()), stations)
        assertEquals(64, slugs.size)
        assertEquals("station-1", slugs.first())
        assertEquals("station-64", slugs.last())
    }

    private fun stop(uuid: String, id: Int, name: String) = StopStation(
        uuid = uuid, station = TrainStation(id = id, name = name),
        departurePlanned = "2026-10-06T10:00:00Z")

    private fun checkin() = CheckinInfo(hafasId = null, category = "bus", mode = null, lineName = "RE1",
        distanceMeters = null, points = null, duration = null, origin = first, destination = last,
        operator = null, trip = 1, tripUuid = "trip", number = null, routeColor = null, routeTextColor = null,
        journeyNumber = null, manualDeparture = null, manualArrival = null)
}
