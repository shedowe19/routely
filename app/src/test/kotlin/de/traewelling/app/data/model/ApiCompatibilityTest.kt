package de.traewelling.app.data.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses intentionally omit deprecated compatibility fields from the API. */
class ApiCompatibilityTest {
    private val gson = Gson()

    @Test
    fun statusUsesCheckinUserAndNestedStationResources() {
        val status = currentStatus()
        val checkin = requireNotNull(status.checkin)
        val origin = requireNotNull(checkin.origin)
        val destination = requireNotNull(checkin.destination)

        assertEquals("test-traveller", status.user?.username)
        assertEquals("78b1027f-0710-42b1-a8f4-5909f1b75d6f", status.user?.uuid)
        assertEquals("RE 1", checkin.lineName)
        assertEquals("baec4235-3cb6-4d3c-8f76-d17081c22e53", checkin.tripUuid)
        assertEquals(91001, origin.id)
        assertEquals(80, origin.stationId)
        assertEquals("Schwerin Hbf", origin.stationName)
        assertEquals(81, destination.stationId)
        assertEquals("Hamburg Hbf", destination.stationName)
        assertEquals("2026-10-05T08:57:00+00:00", origin.effectiveDeparture)
        assertEquals("2026-10-05T10:26:00+00:00", destination.effectiveArrival)
        assertEquals("81379d66-6f6a-462b-a0f0-68208b2cf9f1", checkin.operator?.id)
    }

    @Test
    fun feedUsesCurrentStatusShapeWithoutTrainAndUserDetails() {
        val response = gson.fromJson(
            """{"data":[${currentStatusJson()}],"links":null,"meta":null}""",
            StatusListResponse::class.java
        )

        val status = requireNotNull(response.data).single()
        assertEquals(731, status.id)
        assertEquals("Test Traveller", status.user?.displayName)
        assertEquals("Hamburg Hbf", status.checkin?.destination?.stationName)
    }

    @Test
    fun userParsesStableUuidAndMastodonServer() {
        val user = requireNotNull(readFixture("user-current.json", UserResponse::class.java).data)

        assertEquals(42, user.id)
        assertEquals("78b1027f-0710-42b1-a8f4-5909f1b75d6f", user.uuid)
        assertEquals("https://example.com/avatar.png", user.profilePicture)
        assertEquals(116000L, user.totalDistance)
        assertEquals("example.social", user.mastodon?.server)
    }

    @Test
    fun stationIdentifiersProvideIbnrRilAndLocalCodeOrigin() {
        val origin = requireNotNull(currentStatus().checkin?.origin)
        val station = requireNotNull(origin.station)

        assertEquals(8010324L, station.ibnr)
        assertEquals("WS", station.rilIdentifier)
        assertEquals("8010324", origin.stationIdentifier("de_db_ibnr"))
        val localCode = requireNotNull(station.identifiers).single { it.type == "local_code" }
        assertEquals("SH", localCode.identifier)
        assertEquals("test_network", localCode.origin)
    }

    @Test
    fun missingStationIdentifiersAreSafe() {
        val destination = requireNotNull(currentStatus().checkin?.destination)
        val station = requireNotNull(destination.station)

        assertNull(station.ibnr)
        assertNull(station.rilIdentifier)
        assertNull(destination.stationIdentifier("de_db_ibnr"))
        assertNull(TrainStation().identifier("local_code"))
        assertNull(TrainStation(identifiers = emptyList()).identifier("de_db_ibnr"))
    }

    @Test
    fun nonNumericIbnrDoesNotCrash() {
        val station = TrainStation(identifiers = listOf(StationIdentifier("de_db_ibnr", "invalid")))

        assertNull(station.ibnr)
    }

    @Test
    fun operatorAlsoAcceptsLegacyNumericIdsDuringMigration() {
        val operator = gson.fromJson(
            """{"id":7,"name":"Legacy operator","uuid":"81379d66-6f6a-462b-a0f0-68208b2cf9f1"}""",
            StopOperator::class.java
        )

        assertEquals("7", operator.id)
        assertEquals("81379d66-6f6a-462b-a0f0-68208b2cf9f1", operator.uuid)
    }

    @Test
    fun departuresUseStationDirectionAndTimestampDifference() {
        val departure = requireNotNull(readFixture("departures-current.json", DepartureResponse::class.java).data).single()

        assertEquals("Hamburg Hbf", departure.direction)
        assertEquals("RE 1", departure.line?.name)
        assertEquals(80, departure.station?.id)
        assertEquals("Schwerin Hbf", departure.station?.name)
        assertEquals(5, departure.delayMinutes)
    }

    @Test
    fun departuresMayBeEmptyWhenTravelTypeHasNoMatches() {
        val response = gson.fromJson(
            """{"data":[],"meta":{"availableTravelTypes":["regional"]}}""",
            DepartureResponse::class.java
        )

        assertTrue(requireNotNull(response.data).isEmpty())
    }

    @Test
    fun departureDelayHandlesEarlyAndOnTimeDepartures() {
        val departure = currentDeparture()

        assertEquals(-2, departure.copy(realWhen = "2026-10-05T08:52:00Z").delayMinutes)
        assertEquals(0, departure.copy(realWhen = "2026-10-05T08:54:00Z").delayMinutes)
    }

    @Test
    fun departureDelayRequiresValidRealAndPlannedTimes() {
        val departure = currentDeparture()

        assertNull(departure.copy(realWhen = null).delayMinutes)
        assertNull(departure.copy(plannedWhen = null).delayMinutes)
        assertNull(departure.copy(realWhen = "invalid").delayMinutes)
        assertNull(departure.copy(plannedWhen = "invalid").delayMinutes)
    }

    @Test
    fun tripUsesNestedStationsAndStopoverUuid() {
        val trip = requireNotNull(readFixture("trip-current.json", TripResponse::class.java).data)
        val stops = requireNotNull(trip.stopovers)

        assertEquals("baec4235-3cb6-4d3c-8f76-d17081c22e53", trip.uuid)
        assertEquals("81379d66-6f6a-462b-a0f0-68208b2cf9f1", trip.operator?.id)
        assertEquals(listOf(80, 82, 81), stops.map { it.stationId })
        assertEquals("Schwerin Mitte", stops[1].stationName)
        assertTrue(stops.first().matchesStopover(currentStatus().checkin?.origin))
        assertTrue(stops.last().matchesStopover(currentStatus().checkin?.destination))
    }

    @Test
    fun stopoverTimesPreferRealtimeAndFallBackToPlanned() {
        val stop = stop(80, "2026-10-05T08:54:00Z").copy(
            arrivalPlanned = "2026-10-05T08:52:00Z",
            arrivalReal = "2026-10-05T08:55:00Z",
            departureReal = "2026-10-05T08:57:00Z"
        )

        assertEquals("2026-10-05T08:55:00Z", stop.effectiveArrival)
        assertEquals("2026-10-05T08:57:00Z", stop.effectiveDeparture)
        assertEquals("2026-10-05T08:52:00Z", stop.copy(arrivalReal = null).effectiveArrival)
        assertEquals("2026-10-05T08:54:00Z", stop.copy(departureReal = null).effectiveDeparture)
        assertNull(StopStation().effectiveArrival)
        assertNull(StopStation().effectiveDeparture)
    }

    @Test
    fun stopoverMatchingDistinguishesRepeatedStationVisitsByUuid() {
        val first = stop(80, "2026-10-05T08:54:00Z", "first-visit")
        val second = stop(80, "2026-10-05T10:54:00Z", "second-visit")

        assertFalse(first.matchesStopover(second))
        assertTrue(second.matchesStopover(second.copy(departureReal = "2026-10-05T10:58:00Z")))
        assertEquals(1, listOf(first, second).indexOfFirst { it.matchesStopover(second) })
    }

    @Test
    fun stopoverMatchingWithoutUuidUsesStationAndPlannedInstant() {
        val first = stop(80, "2026-10-05T08:54:00Z")
        val same = first.copy(id = 9999, departurePlanned = "2026-10-05T10:54:00+02:00")
        val laterVisit = first.copy(departurePlanned = "2026-10-05T10:54:00Z")

        assertTrue(first.matchesStopover(same))
        assertFalse(first.matchesStopover(laterVisit))
        assertFalse(first.matchesStopover(first.copy(station = TrainStation(id = 81))))
    }

    @Test
    fun stopoverMatchingCanUsePlannedArrivalAtTerminal() {
        val terminal = StopStation(
            station = TrainStation(id = 81),
            arrivalPlanned = "2026-10-05T10:26:00Z"
        )

        assertTrue(terminal.matchesStopover(terminal.copy(arrivalPlanned = "2026-10-05T12:26:00+02:00")))
        assertFalse(terminal.matchesStopover(terminal.copy(arrivalPlanned = "2026-10-05T10:27:00Z")))
    }

    @Test
    fun missingStopoverIdentityDoesNotMatchOtherMissingIdentity() {
        val noStationIdentity = StopStation(departurePlanned = "2026-10-05T08:54:00Z")

        assertFalse(noStationIdentity.matchesStopover(noStationIdentity.copy()))
        assertFalse(StopStation().matchesStopover(StopStation()))
        assertFalse(stop(80, null).matchesStopover(stop(80, null)))
        assertFalse(noStationIdentity.matchesStopover(null))
    }

    @Test
    fun stopoversEndpointUsesTripKeyedMap() {
        val stopsJson = JsonParser.parseString(fixture("trip-current.json"))
            .asJsonObject.getAsJsonObject("data").getAsJsonArray("stopovers")
        val response = gson.fromJson(
            """{"data":{"530":$stopsJson,"531":[]}}""",
            StopoversResponse::class.java
        )

        assertEquals(setOf("530", "531"), response.data?.keys)
        assertEquals(listOf(80, 82, 81), response.allStopovers().map { it.stationId })
        assertTrue(StopoversResponse(null).allStopovers().isEmpty())
    }

    @Test
    fun checkinSuccessUsesReasonAndCurrentStatusWithoutAdditionalPoints() {
        val response = gson.fromJson(
            """{"data":{"status":${currentStatusJson()},"points":{"points":17,"calculation":{"base":5,"reason":2,"distance":10,"factor":1.0}}}}""",
            CheckInResponse::class.java
        )
        val result = requireNotNull(response.data)

        assertEquals("Hamburg Hbf", result.status?.checkin?.destination?.stationName)
        assertEquals(17, result.points?.points)
        assertEquals(2, result.points?.calculation?.reason)
    }

    @Test
    fun destinationUpdateSerializesStationIdAndPlannedArrivalTogether() {
        val request = UpdateStatusRequest(
            destination = 81,
            destinationArrivalPlanned = "2026-10-05T10:26:00Z"
        )
        val json = gson.toJsonTree(request).asJsonObject

        assertEquals(81, json.get("destinationId").asInt)
        assertEquals("2026-10-05T10:26:00Z", json.get("destinationArrivalPlanned").asString)
        assertFalse(json.has("destination"))
        assertFalse(json.has("manualArrival"))
    }

    @Test
    fun textOnlyStatusUpdateOmitsDestinationFields() {
        val json = gson.toJsonTree(UpdateStatusRequest(body = "Neuer Text")).asJsonObject

        assertEquals("Neuer Text", json.get("body").asString)
        assertFalse(json.has("destinationId"))
        assertFalse(json.has("destinationArrivalPlanned"))
        assertFalse(json.has("manualDeparture"))
        assertFalse(json.has("manualArrival"))
    }

    @Test
    fun checkinConflictUsesFullStatusesWithoutLegacyMessageFields() {
        val response = gson.fromJson(
            """{"data":{"conflicts":[${currentStatusJson()}]}}""",
            CheckInConflictResponse::class.java
        )
        val conflicts = requireNotNull(response.data?.conflicts)
        val error = CheckInConflictException(conflicts)

        assertEquals(731, conflicts.single().id)
        assertEquals("Hamburg Hbf", conflicts.single().checkin?.destination?.stationName)
        assertTrue(requireNotNull(error.message).contains("RE 1 nach Hamburg Hbf"))
        assertTrue(requireNotNull(error.message).contains("731"))
    }

    @Test
    fun emptyCheckinConflictHasReadableFallback() {
        val error = CheckInConflictException(emptyList())

        assertTrue(requireNotNull(error.message).contains("bestehenden Check-in"))
    }

    @Test
    fun deduplicationKeepsDistinctStationsEvenWhenPlannedTimesAreEqual() {
        val first = stop(80, "2026-10-05T08:54:00Z")
        val second = stop(81, "2026-10-05T08:54:00Z")

        assertEquals(listOf(first, second), listOf(first, second).deduplicate())
    }

    @Test
    fun deduplicationKeepsRepeatedVisitsAndStopsWithoutStationIdentity() {
        val first = stop(80, "2026-10-05T08:54:00Z")
        val second = stop(80, "2026-10-05T10:54:00Z")
        val unknown = StopStation(departurePlanned = "2026-10-05T10:54:00Z")

        assertEquals(listOf(first, second), listOf(first, second).deduplicate())
        assertEquals(2, listOf(unknown, unknown.copy()).deduplicate().size)
        assertEquals(2, listOf(stop(80, null), stop(80, null)).deduplicate().size)
    }

    @Test
    fun deduplicationMergesSameStationAndPlannedTimesPreferringPlatform() {
        val first = stop(80, "2026-10-05T08:54:00Z")
        val enriched = first.copy(departurePlatformPlanned = "3")

        assertEquals(listOf(enriched), listOf(first, enriched).deduplicate())
    }

    @Test
    fun deduplicationPreservesDistinctStopoverUuidsAndOriginMatching() {
        val first = stop(80, "2026-10-05T08:54:00Z", "5f4a5ae8-0bd4-42a8-b1c3-8b53494343ad")
        val second = first.copy(
            uuid = "2d9f3c6e-418c-4080-8b35-152922a90842",
            departurePlatformPlanned = "3"
        )
        val result = listOf(first, second).deduplicate()

        assertEquals(listOf(first, second), result)
        assertEquals(0, result.indexOfFirst { it.matchesStopover(first) })
        assertEquals(1, result.indexOfFirst { it.matchesStopover(second) })
    }

    private fun currentStatus(): Status =
        requireNotNull(readFixture("status-current.json", SingleStatusResponse::class.java).data)

    private fun currentDeparture(): DepartureTrip =
        requireNotNull(readFixture("departures-current.json", DepartureResponse::class.java).data).single()

    private fun currentStatusJson(): String =
        JsonParser.parseString(fixture("status-current.json")).asJsonObject.get("data").toString()

    private fun <T> readFixture(name: String, type: Class<T>): T = gson.fromJson(fixture(name), type)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("traewelling/$name")) {
            "Missing API fixture: $name"
        }.bufferedReader().use { it.readText() }

    private fun stop(stationId: Int, departure: String?, uuid: String? = null): StopStation =
        StopStation(
            id = 90000 + stationId,
            uuid = uuid,
            station = TrainStation(id = stationId, name = "Station $stationId"),
            departurePlanned = departure
        )
}
