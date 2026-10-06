package de.traewelling.app.data.repository

import de.traewelling.app.data.model.StationIdentifier
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyStationIdentityTest {
    @Test fun nearbyStationsWithSharedNameTokensKeepTheirSeparateBoardingIds() {
        val stations = listOf(
            TrainStation(id = 1, name = "Kaarster See", latitude = 51.22, longitude = 6.6),
            TrainStation(id = 2, name = "Kaarster See, Kaarst", latitude = 51.2201, longitude = 6.6001)
        )
        assertEquals(listOf(1, 2), deduplicateNearbyStations(stations).map { it.id })
    }

    @Test fun repeatedAuthoritativeIdKeepsTheAlreadySortedNearestEntry() {
        val first = TrainStation(id = 1, name = "Nearest")
        assertEquals(listOf(first), deduplicateNearbyStations(listOf(first, first.copy(name = "Alternative label"))))
    }

    @Test fun identicalNamesAndCoordinatesWithoutIdentityDoNotProveDuplicates() {
        val unknown = TrainStation(name = "Hauptbahnhof", latitude = 51.0, longitude = 6.0)
        assertEquals(2, deduplicateNearbyStations(listOf(unknown, unknown)).size)
    }

    @Test fun uuidCanIdentifyDuplicateRecordsWhenInternalIdsAreAbsent() {
        val station = TrainStation(uuid = "station-uuid", name = "Hbf")
        assertEquals(listOf(station), deduplicateNearbyStations(listOf(station, station.copy(name = "Hauptbahnhof"))))
    }

    @Test fun conflictingInternalIdsAreNotCollapsedBySharedUuidOrExternalIdentifier() {
        val identifiers = listOf(StationIdentifier("de_db_ibnr", "8000001"))
        val first = TrainStation(id = 1, uuid = "shared", identifiers = identifiers)
        assertEquals(2, deduplicateNearbyStations(listOf(first, first.copy(id = 2))).size)
    }

    @Test fun invalidIdAndBlankUuidDoNotCollapseUnknownStations() {
        val station = TrainStation(id = 0, uuid = " ", name = "Unknown")
        assertEquals(2, deduplicateNearbyStations(listOf(station, station)).size)
    }
}
