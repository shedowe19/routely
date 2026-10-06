package de.traewelling.app.data.sev

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.SevMap
import de.traewelling.app.data.model.SevPoint
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SevStopResolverTest {
    private val now = instant("2026-10-06T10:00:00Z")
    private val essen = stop(1, "Essen Hbf", 51.451, 7.014, "essen-visit")
    private val muelheim = stop(2, "Mülheim (Ruhr) Hbf", 51.431, 6.886, "muelheim-visit")
    private val duisburg = stop(3, "Duisburg Hbf", 51.429, 6.777, "duisburg-visit")
    private val oberhausen = stop(4, "Oberhausen Hbf", 51.474, 6.852, "oberhausen-visit")
    private val duesseldorf = stop(5, "Düsseldorf Hbf", 51.221, 6.792, "duesseldorf-visit")
    private val toDuisburg = SevPoint("sev.136938", 51.43222557, 6.88553272,
        "Richtung Duisburg \nbis zum 09.10.2026: Richtung Oberhausen", "09.09.2026, 08:21")
    private val toEssen = SevPoint("sev.136949", 51.43175246, 6.88538831, "Richtung Essen")
    private val temporary = "Temporäre Ersatzhaltestellen vom 04.09 bis 30.10.2026:"

    @Test fun detectsRegionalReplacementBusesButNotTrainsOrOrdinaryBuses() {
        assertTrue(SevStopResolver.isReplacementBus(checkin()))
        assertTrue(SevStopResolver.isReplacementBus(checkin().copy(lineName = "Bus RE1")))
        assertTrue(SevStopResolver.isReplacementBus(checkin().copy(category = "regional", mode = "BUS", lineName = "RB 31")))
        assertFalse(SevStopResolver.isReplacementBus(checkin().copy(category = "regional", mode = "train")))
        assertFalse(SevStopResolver.isReplacementBus(checkin().copy(lineName = "SB1")))
        assertFalse(SevStopResolver.isReplacementBus(checkin().copy(lineName = "RE")))
    }

    @Test fun officialSlugTransliteratesGermanStationNames() {
        assertEquals("muelheim-ruhr-hbf", SevStopResolver.stationSlug(muelheim.station!!))
        assertEquals("duesseldorf-hbf", SevStopResolver.stationSlug(duesseldorf.station!!))
        assertNull(SevStopResolver.stationSlug(TrainStation(name = "  ")))
    }

    @Test fun freshSingleUnlabelledPointUsesPhysicalStopWithoutMutatingApiStation() {
        val point = SevPoint("sev.101840", 51.45018831, 7.0101172, null, "27.05.2025, 09:19")
        val result = resolve(listOf(essen, muelheim), map(essen, listOf(point)))
        val info = result.getValue(SevStopResolver.visitKey(essen))
        assertTrue(info.hasCoordinates)
        assertEquals(point.latitude, info.latitude!!, 0.0)
        assertEquals(point.longitude, info.longitude!!, 0.0)
        assertEquals(51.451, essen.station!!.latitude!!, 0.0)
        assertEquals("essen-visit", essen.uuid)
        assertEquals(1, essen.stationId)
    }

    @Test fun futureRouteProvesDuisburgDirectionRatherThanNearestCandidate() {
        val info = resolve(listOf(muelheim, duisburg), map(muelheim, listOf(toEssen, toDuisburg), listOf(temporary)))
            .getValue("muelheim-visit")
        assertEquals(toDuisburg.latitude, info.latitude!!, 0.0)
        assertEquals(toDuisburg.label, info.label)
    }

    @Test fun reverseRouteSelectsEssenDirection() {
        val info = resolve(listOf(muelheim, essen), map(muelheim, listOf(toDuisburg, toEssen), listOf(temporary)))
            .getValue("muelheim-visit")
        assertEquals(toEssen.longitude, info.longitude!!, 0.0)
    }

    @Test fun endOfFullRouteCannotInventArrivalPointFromDepartureLabels() {
        val map = map(duisburg, listOf(SevPoint("a", 51.42804102, 6.77808449, "Richtung Essen / Oberhausen"),
            SevPoint("b", 51.42987326, 6.77724749, "Richtung Düsseldorf")), listOf(temporary))
        val info = resolve(listOf(essen, muelheim, duisburg), map).getValue("duisburg-visit")
        assertFalse(info.hasCoordinates)
        assertNotNull(info.reason)
        assertTrue(info.guidance.contains("04.09"))
    }

    @Test fun fullRouteLookaheadBeyondCheckinDestinationCanConfirmDirection() {
        val map = map(duisburg, listOf(SevPoint("a", 51.42804102, 6.77808449, "Richtung Essen / Oberhausen"),
            SevPoint("b", 51.42987326, 6.77724749, "Richtung Düsseldorf")), listOf(temporary))
        val result = SevStopResolver.resolve(checkin().copy(destination = duisburg),
            listOf(essen, muelheim, duisburg, duesseldorf), mapOf(map.slug to map), now)
        assertEquals("Richtung Düsseldorf", result.getValue("duisburg-visit").label)
    }

    @Test fun sameDirectionOnTwoPointsIsAmbiguousEvenIfOneIsCloser() {
        val map = map(muelheim, listOf(toDuisburg, toDuisburg.copy(id = "other", latitude = 51.431)), listOf(temporary))
        assertFalse(resolve(listOf(muelheim, duisburg), map).getValue("muelheim-visit").hasCoordinates)
    }

    @Test fun temporaryExtraOberhausenDirectionExpiresWithoutExpiringDuisburg() {
        val later = instant("2026-10-10T10:00:00Z")
        val currentMuelheim = muelheim.copy(departurePlanned = Instant.ofEpochMilli(later).toString())
        val map = map(currentMuelheim, listOf(toDuisburg, toEssen), listOf(temporary)).copy(fetchedAtMillis = later)
        val expired = SevStopResolver.resolve(checkin(), listOf(currentMuelheim, oberhausen), mapOf(map.slug to map), later)
        assertFalse(expired.getValue("muelheim-visit").hasCoordinates)
        val stillValid = SevStopResolver.resolve(checkin(), listOf(currentMuelheim, duisburg), mapOf(map.slug to map), later)
        assertTrue(stillValid.getValue("muelheim-visit").hasCoordinates)
    }

    @Test fun extraOberhausenDirectionIsValidThroughItsLastBerlinDay() {
        val lastDay = instant("2026-10-09T21:59:59Z")
        val current = muelheim.copy(departurePlanned = Instant.ofEpochMilli(lastDay).toString())
        val map = map(current, listOf(toDuisburg, toEssen), listOf(temporary)).copy(fetchedAtMillis = lastDay)
        val info = SevStopResolver.resolve(checkin(), listOf(current, oberhausen), mapOf(map.slug to map), lastDay)
            .getValue("muelheim-visit")
        assertTrue(info.hasCoordinates)
    }

    @Test fun temporaryWholeMapWindowExpiresDespiteFreshDownloadAndOldVersion() {
        val expired = instant("2026-10-31T10:00:00Z")
        val map = map(essen, listOf(toDuisburg.copy(label = null)), listOf(temporary)).copy(fetchedAtMillis = expired)
        val info = SevStopResolver.resolve(checkin(), listOf(essen), mapOf(map.slug to map), expired).getValue("essen-visit")
        assertFalse(info.hasCoordinates)
        assertTrue(info.reason!!.contains("Fahrtzeitraum"))
    }

    @Test fun temporaryMapWindowDoesNotValidateFutureVisitOutsideItsDates() {
        val laterStop = muelheim.copy(departurePlanned = "2026-10-31T10:00:00Z")
        val info = resolve(listOf(laterStop, duisburg), map(muelheim, listOf(toDuisburg, toEssen), listOf(temporary)))
            .getValue("muelheim-visit")
        assertFalse(info.hasCoordinates)
    }

    @Test fun malformedOrUnspecifiedTemporaryWindowKeepsGuidanceButNoCoordinates() {
        for (notice in listOf("Temporäre Ersatzhaltestellen", "Temporäre Ersatzhaltestellen vom 31.02 bis 30.10.2026")) {
            val info = resolve(listOf(essen), map(essen, listOf(toDuisburg.copy(label = null)), listOf(notice)))
                .getValue("essen-visit")
            assertFalse(info.hasCoordinates)
            assertEquals(notice, info.guidance)
        }
    }

    @Test fun futureStartWithoutTemporaryKeywordDoesNotEnableSinglePoint() {
        val notice = "Die Ersatzhaltestelle gilt ab dem 31.10.2026."
        val info = resolve(listOf(essen), map(essen, listOf(SevPoint("sev", 51.45018831, 7.0101172, null)), listOf(notice)))
            .getValue("essen-visit")
        assertFalse(info.hasCoordinates)
        assertEquals(notice, info.guidance)
        assertTrue(info.reason!!.contains("Gültigkeit"))
    }

    @Test fun expiredEndWithoutTemporaryKeywordDoesNotEnableSinglePoint() {
        val notice = "Ersatzhaltestelle bis zum 05.10.2026"
        val info = resolve(listOf(essen), map(essen, listOf(SevPoint("sev", 51.45018831, 7.0101172, null)), listOf(notice)))
            .getValue("essen-visit")
        assertFalse(info.hasCoordinates)
        assertEquals(notice, info.guidance)
        assertTrue(info.reason!!.contains("Gültigkeit"))
    }

    @Test fun mapMustBeFreshAndNotFutureDatedEvenForSinglePoint() {
        for (fetched in listOf(now - 24 * 60 * 60 * 1000L - 1, now + 1, 0L)) {
            val map = map(essen, listOf(toDuisburg.copy(label = null))).copy(fetchedAtMillis = fetched)
            assertFalse(resolve(listOf(essen), map).getValue("essen-visit").hasCoordinates)
        }
    }

    @Test fun sourceCenterMustMatchApiStationAndCannotServeAnotherStation() {
        val point = SevPoint("sev", 51.45018831, 7.0101172, null)
        val wrong = map(essen, listOf(point)).copy(stationLatitude = 52.0)
        assertFalse(resolve(listOf(essen), wrong).getValue("essen-visit").hasCoordinates)
        val missing = essen.copy(station = essen.station!!.copy(latitude = null))
        assertFalse(resolve(listOf(missing), map(essen, listOf(point))).getValue("essen-visit").hasCoordinates)
    }

    @Test fun unexpectedSourceAndInvalidPointNeverOverrideApiCoordinates() {
        for (map in listOf(
            map(essen, listOf(toDuisburg.copy(label = null))).copy(sourceUrl = "https://example.org/essen-hbf/karte"),
            map(essen, listOf(toDuisburg.copy(label = null, latitude = Double.NaN))),
            map(essen, listOf(toDuisburg.copy(label = null, latitude = 52.0))),
            map(essen, listOf(toDuisburg.copy(label = null, id = "")))
        )) assertFalse(resolve(listOf(essen), map).getValue("essen-visit").hasCoordinates)
    }

    @Test fun visitKeysDoNotDependOnIndexAndSeparateRepeatedVisits() {
        val first = essen.copy(uuid = null)
        val second = first.copy(departurePlanned = "2026-10-06T11:00:00Z")
        assertNotEquals(SevStopResolver.visitKey(first), SevStopResolver.visitKey(second))
        val result = resolve(listOf(muelheim, first, second), map(essen, listOf(SevPoint("sev", 51.45018831, 7.0101172, null))))
        assertTrue(result.getValue(SevStopResolver.visitKey(first)).hasCoordinates)
        assertTrue(result.getValue(SevStopResolver.visitKey(second)).hasCoordinates)
    }

    @Test fun duplicatedVisitIdentityIsNotSilentlyResolved() {
        val result = resolve(listOf(essen, essen), map(essen, listOf(SevPoint("sev", 51.45018831, 7.0101172, null))))
        assertFalse(result.getValue("essen-visit").hasCoordinates)
    }

    @Test fun missingUuidNeedsParseableVisitTimeRatherThanBlankFallbackKey() {
        val unidentified = essen.copy(uuid = null, departurePlanned = "", arrivalPlanned = null)
        val result = resolve(listOf(unidentified), map(essen, listOf(SevPoint("sev", 51.45018831, 7.0101172, null))))
        assertFalse(result.getValue(SevStopResolver.visitKey(unidentified)).hasCoordinates)
    }

    @Test fun cancelledVisitsAndNonReplacementJourneysHaveNoAssignments() {
        val map = map(essen, listOf(SevPoint("sev", 51.45018831, 7.0101172, null)))
        assertTrue(resolve(listOf(essen.copy(cancelled = true)), map).isEmpty())
        assertTrue(SevStopResolver.resolve(checkin().copy(category = "regional"), listOf(essen), mapOf(map.slug to map), now).isEmpty())
    }

    private fun resolve(route: List<StopStation>, map: SevMap) =
        SevStopResolver.resolve(checkin(), route, mapOf(map.slug to map), now)

    private fun map(stop: StopStation, points: List<SevPoint>, notes: List<String> = emptyList()): SevMap {
        val station = stop.station!!
        val slug = SevStopResolver.stationSlug(station)!!
        return SevMap(slug, "https://www.bahnhof.de/$slug/karte", station.latitude!!, station.longitude!!, points, notes, now)
    }

    private fun stop(id: Int, name: String, latitude: Double, longitude: Double, uuid: String) =
        StopStation(uuid = uuid, station = TrainStation(id = id, name = name, latitude = latitude, longitude = longitude),
            departurePlanned = Instant.ofEpochMilli(now).toString())

    private fun checkin() = CheckinInfo(hafasId = null, category = "bus", mode = null, lineName = "RE1",
        distanceMeters = null, points = null, duration = null, origin = essen, destination = duisburg,
        operator = null, trip = 1, tripUuid = "trip", number = null, routeColor = null, routeTextColor = null,
        journeyNumber = null, manualDeparture = null, manualArrival = null)

    private fun instant(value: String): Long = Instant.parse(value).toEpochMilli()
}
