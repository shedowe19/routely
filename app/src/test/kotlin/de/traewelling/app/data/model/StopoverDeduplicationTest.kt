package de.traewelling.app.data.model

import org.junit.Assert.*
import org.junit.Test

class StopoverDeduplicationTest {
    private val plannedArrival = "2026-11-03T10:00:00Z"
    private val plannedDeparture = "2026-11-03T10:01:00Z"
    private val arrivalInfo = StopRealtimeInfo(1_000L, "Ankunftsquelle")
    private val departureInfo = StopRealtimeInfo(2_000L, "Abfahrtsquelle")

    private fun stop(name: String = "Bahnhof") = StopStation(
        station = TrainStation(id = 80, name = name),
        arrivalPlanned = plannedArrival,
        departurePlanned = plannedDeparture
    )

    @Test fun complementaryRealtimeFieldsSurviveThePreferredShorterName() {
        val arrival = stop("Langer Bahnhofsname").copy(
            uuid = "visit", arrivalReal = "2026-11-03T10:05:00Z",
            arrivalPlatformReal = "5", cancelled = false,
            arrivalRealtimeInfo = arrivalInfo, arrivalPlatformRealtimeInfo = arrivalInfo,
            cancellationRealtimeInfo = arrivalInfo
        )
        val departure = stop("Bf").copy(departureReal = "2026-11-03T10:06:00Z",
            departurePlatformReal = "6", departureRealtimeInfo = departureInfo,
            departurePlatformRealtimeInfo = departureInfo)
        for (stops in listOf(listOf(arrival, departure), listOf(departure, arrival))) {
            val merged = stops.deduplicate().single()
            assertEquals("Bf", merged.stationName)
            assertEquals("visit", merged.uuid)
            assertEquals(arrival.arrivalReal, merged.arrivalReal)
            assertEquals(departure.departureReal, merged.departureReal)
            assertEquals("5", merged.arrivalPlatformReal)
            assertEquals("6", merged.departurePlatformReal)
            assertEquals(false, merged.cancelled)
            assertEquals(arrivalInfo, merged.arrivalRealtimeInfo)
            assertEquals(departureInfo, merged.departureRealtimeInfo)
            assertEquals(arrivalInfo, merged.arrivalPlatformRealtimeInfo)
            assertEquals(departureInfo, merged.departurePlatformRealtimeInfo)
            assertEquals(arrivalInfo, merged.cancellationRealtimeInfo)
        }
    }

    @Test fun complementaryPlannedPlatformsAndExplicitCancellationSurvive() {
        val first = stop().copy(arrivalPlatformPlanned = "1", cancelled = true,
            cancellationRealtimeInfo = arrivalInfo)
        val second = stop("Bf").copy(departurePlatformPlanned = "2")
        val merged = listOf(first, second).deduplicate().single()
        assertEquals("1", merged.arrivalPlatformPlanned)
        assertEquals("2", merged.departurePlatformPlanned)
        assertEquals(true, merged.cancelled)
        assertEquals(arrivalInfo, merged.cancellationRealtimeInfo)
    }

    @Test fun preferredNameDoesNotDiscardStationCoordinatesOrIdentifiers() {
        val ibnr = StationIdentifier("de_db_ibnr", "8000001")
        val ril = StationIdentifier("de_db_ril100", "ABCD")
        val detailed = stop("Langer Name").copy(station = TrainStation(
            id = 80, uuid = "station", name = "Langer Name", latitude = 50.0, longitude = 8.0,
            identifiers = listOf(ibnr)
        ))
        val preferred = stop("Bf").copy(station = TrainStation(id = 80, name = "Bf", identifiers = listOf(ril)))
        val merged = listOf(detailed, preferred).deduplicate().single()
        assertEquals("Bf", merged.stationName)
        assertEquals("station", merged.station?.uuid)
        assertEquals(50.0, merged.station?.latitude)
        assertEquals(8.0, merged.station?.longitude)
        assertEquals("8000001", merged.stationIdentifier("de_db_ibnr"))
        assertEquals("ABCD", merged.stationIdentifier("de_db_ril100"))
    }

    @Test fun blankPlatformsCannotHideKnownPlatformsOrKeepUnrelatedReadProvenance() {
        val known = stop("Langer Name").copy(platform = "7",
            arrivalPlatformPlanned = "1", departurePlatformPlanned = "2",
            arrivalPlatformReal = "5", departurePlatformReal = "6",
            arrivalPlatformRealtimeInfo = arrivalInfo, departurePlatformRealtimeInfo = departureInfo)
        val short = stop("Bf").copy(platform = "\n",
            arrivalPlatformPlanned = "1", departurePlatformPlanned = " ",
            arrivalPlatformReal = " ", departurePlatformReal = "\t",
            arrivalPlatformRealtimeInfo = departureInfo, departurePlatformRealtimeInfo = arrivalInfo)
        // A known planned platform keeps each short entry the preferred row;
        // the other four blank fields must still take their complementary values.
        for (preferred in listOf(short, short.copy(arrivalPlatformPlanned = " ", departurePlatformPlanned = "2"))) {
            val merged = listOf(preferred, known).deduplicate().single()
            assertEquals("Bf", merged.stationName)
            assertEquals("7", merged.platform)
            assertEquals("1", merged.arrivalPlatformPlanned)
            assertEquals("2", merged.departurePlatformPlanned)
            assertEquals("5", merged.arrivalPlatformReal)
            assertEquals("6", merged.departurePlatformReal)
            assertEquals(arrivalInfo, merged.arrivalPlatformRealtimeInfo)
            assertEquals(departureInfo, merged.departurePlatformRealtimeInfo)
        }
    }

    @Test fun aConflictingValueNeverReceivesTheOtherValuesReadProvenance() {
        val preferred = stop("Bf").copy(arrivalReal = "2026-11-03T10:04:00Z")
        val conflicting = stop("Langer Name").copy(arrivalReal = "2026-11-03T10:08:00Z",
            arrivalRealtimeInfo = arrivalInfo)
        val merged = listOf(preferred, conflicting).deduplicate().single()
        assertEquals(preferred.arrivalReal, merged.arrivalReal)
        assertNull(merged.arrivalRealtimeInfo)
    }

    @Test fun matchingValueCanKeepKnownReadProvenanceWhenThePreferredEntryHasNone() {
        val preferred = stop("Bf").copy(arrivalReal = "2026-11-03T10:04:00Z")
        val same = stop("Langer Name").copy(arrivalReal = preferred.arrivalReal,
            arrivalRealtimeInfo = arrivalInfo)
        val merged = listOf(preferred, same).deduplicate().single()
        assertEquals(preferred.arrivalReal, merged.arrivalReal)
        assertEquals(arrivalInfo, merged.arrivalRealtimeInfo)
    }

    @Test fun separateVisitsOrStationsNeverShareRealtimeFields() {
        val first = stop().copy(uuid = "first", arrivalReal = "2026-11-03T10:04:00Z")
        val otherUuid = first.copy(uuid = "second", arrivalReal = null, departureReal = "2026-11-03T10:08:00Z")
        val otherTime = first.copy(uuid = null, arrivalPlanned = "2026-11-03T11:00:00Z")
        val otherStation = first.copy(station = first.station?.copy(id = 81))
        for (other in listOf(otherUuid, otherTime, otherStation)) {
            assertEquals(listOf(first, other), listOf(first, other).deduplicate())
        }
    }

    @Test fun sameTimeOnNonconsecutiveVisitsDoesNotMergeAcrossAnotherStation() {
        val first = stop().copy(arrivalReal = "2026-11-03T10:04:00Z")
        val middle = stop().copy(station = TrainStation(id = 81))
        val later = stop().copy(departureReal = "2026-11-03T10:08:00Z")
        assertEquals(listOf(first, middle, later), listOf(first, middle, later).deduplicate())
    }
}
