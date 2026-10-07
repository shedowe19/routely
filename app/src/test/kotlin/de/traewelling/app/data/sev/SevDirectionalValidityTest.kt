package de.traewelling.app.data.sev

import com.google.gson.Gson
import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.SevMap
import de.traewelling.app.data.model.SevPoint
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import de.traewelling.app.service.RoadRouteSelection
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SevDirectionalValidityTest {
    private val gson = Gson()
    private val now = instant("2026-10-07T05:00:00Z")
    private val duisburg = stop(1, "Duisburg Hbf", 51.429785, 6.775903, "duisburg", now)
    private val muelheim = stop(2, "Mülheim (Ruhr) Hbf", 51.431342, 6.88651, "muelheim", now)
    private val essen = stop(3, "Essen Hbf", 51.451355, 7.014793, "essen", now)
    private val oberhausen = stop(4, "Oberhausen Hbf", 51.474, 6.852, "oberhausen", now)
    private val toDuisburg = SevPoint("sev.136938", 51.43222557, 6.88553272,
        "Richtung Duisburg\nbis zum 09.10.2026: Richtung Oberhausen")
    private val toEssen = SevPoint("sev.136949", 51.43175246, 6.88538831, "Richtung Essen")
    private val mainWindow = "Temporäre Ersatzhaltestellen vom 04.09 bis 30.10.2026:"
    // Both temporal clauses and the dated point label are public map data.
    // Unrelated walking directions are unnecessary for this regression.
    private val extraDirection = "Bis zum 09.10.2026 fährt ebenfalls Ersatzverkehr Richtung Oberhausen."
    private val notice = "$mainWindow\n\nWegbeschreibung zur Ersatzhaltestelle. $extraDirection"

    @Test fun parsedScreenshotMapsProvideBothPhysicalRoadSegmentsTowardsEssen() {
        val route = listOf(duisburg, muelheim, essen)
        val maps = listOf(
            map(duisburg, listOf(
                SevPoint("sev.135989", 51.42804102, 6.77808449, "Richtung Essen / Oberhausen"),
                SevPoint("sev.135978", 51.42987326, 6.77724749, "Richtung Düsseldorf")), mainWindow),
            map(muelheim, listOf(toDuisburg, toEssen), notice),
            map(essen, listOf(SevPoint("sev.101840", 51.45018831, 7.0101172, null)), "Wegbeschreibung")
        ).associateBy { it.slug }
        val resolved = SevStopResolver.resolve(checkin(route), route, maps, now)
        val middle = resolved.getValue("muelheim")
        assertEquals(notice, middle.guidance)
        assertNull(middle.reason)
        assertEquals("Richtung Essen", middle.label)
        assertEquals(toEssen.latitude, middle.latitude!!, 0.0)
        val pairs = RoadRouteSelection.allPairs(checkin(route), route, route.map { it.uuid!! }, resolved)
        assertEquals(listOf("duisburg" to "muelheim", "muelheim" to "essen"),
            pairs.map { it.fromKey to it.toKey })
        assertEquals(2, RoadRouteSelection.aroundCursor(pairs, "duisburg").size)
        assertEquals(toEssen.longitude, pairs.first().to.longitude, 0.0)
        assertNotEquals(muelheim.station!!.longitude, pairs.first().to.longitude)
    }

    @Test fun extraDirectionExpiryDoesNotExpireEssenOrDuisburgPoints() {
        for (date in listOf("2026-10-07T05:00:00Z", "2026-10-10T05:00:00Z")) {
            val clock = instant(date)
            val current = muelheim.copy(departurePlanned = date)
            for ((next, expected) in listOf(essen to toEssen, duisburg to toDuisburg)) {
                val info = resolve(listOf(current, next), notice, clock)
                assertNull(info.reason)
                assertEquals(expected.latitude, info.latitude!!, 0.0)
            }
        }
    }

    @Test fun datedExtraDirectionStillExpiresBeforeAnOpposingLaterPlace() {
        val clock = instant("2026-10-10T05:00:00Z")
        val current = muelheim.copy(departurePlanned = Instant.ofEpochMilli(clock).toString())
        val info = resolve(listOf(current, oberhausen, essen), notice, clock)
        assertFalse(info.hasCoordinates)
        assertTrue(info.reason!!.contains("richtungsabhängige"))
    }

    @Test fun datedExtraDirectionRequiresBothCurrentAndPlannedDate() {
        assertTrue(resolve(listOf(muelheim, oberhausen), notice).hasCoordinates)
        val future = muelheim.copy(departurePlanned = "2026-10-10T05:00:00Z")
        assertFalse(resolve(listOf(future, oberhausen, essen), notice).hasCoordinates)
    }

    @Test fun unknownGeneralRestrictionsStillPreventPhysicalRoadPairs() {
        for (restriction in listOf("Die Ersatzhaltestelle gilt nur bis zum 05.10.2026.",
            "Die Ersatzhaltestelle gilt erst ab dem 31.10.2026.")) {
            val route = listOf(muelheim, essen)
            val publicMap = map(muelheim, listOf(toDuisburg, toEssen), "$notice\n$restriction")
            val resolved = SevStopResolver.resolve(checkin(route), route, mapOf(publicMap.slug to publicMap), now)
            assertFalse(resolved.getValue("muelheim").hasCoordinates)
            assertTrue(resolved.getValue("muelheim").reason!!.contains("Gültigkeit"))
            assertTrue(RoadRouteSelection.allPairs(checkin(route), route, route.map { it.uuid!! }, resolved).isEmpty())
        }
    }

    @Test fun unconfirmedOrDifferentDirectionalDatesDoNotBypassMapValidity() {
        for (extra in listOf(
            extraDirection.replace("09.10.2026", "08.10.2026"),
            extraDirection.replace("Oberhausen", "Düsseldorf"),
            extraDirection.replace("09.10.2026", "31.02.2026"),
            extraDirection.replace("09.10.2026", "09.10.20260"),
            extraDirection.replace("Oberhausen.", "Oberhausen, danach nicht mehr.")
        )) {
            val info = resolve(listOf(muelheim, essen), "$mainWindow\n$extra")
            assertFalse(info.hasCoordinates)
            assertTrue(info.reason!!.contains("Gültigkeit"))
        }
        assertFalse(resolve(listOf(muelheim, essen), notice,
            points = listOf(toDuisburg.copy(label = "Richtung Duisburg"), toEssen)).hasCoordinates)
    }

    @Test fun conflictingPointsCannotConfirmTheSupplementAsASingleDirection() {
        val info = resolve(listOf(muelheim, essen), notice,
            points = listOf(toDuisburg, toDuisburg.copy(id = "other", latitude = 51.4323), toEssen))
        assertFalse(info.hasCoordinates)
        assertTrue(info.reason!!.contains("Gültigkeit"))
    }

    @Test fun validDirectionalSupplementCannotReviveAnExpiredMainWindow() {
        val clock = instant("2026-10-31T05:00:00Z")
        val current = muelheim.copy(departurePlanned = Instant.ofEpochMilli(clock).toString())
        val info = resolve(listOf(current, essen), notice, clock)
        assertFalse(info.hasCoordinates)
        assertTrue(info.reason!!.contains("Fahrtzeitraum"))
    }

    private fun resolve(route: List<StopStation>, text: String, clock: Long = now,
                        points: List<SevPoint> = listOf(toDuisburg, toEssen)) =
        map(route.first(), points, text, clock).let { publicMap ->
            SevStopResolver.resolve(checkin(route), route, mapOf(publicMap.slug to publicMap), clock)
                .getValue(SevStopResolver.visitKey(route.first()))
        }

    private fun map(stop: StopStation, points: List<SevPoint>, text: String, clock: Long = now): SevMap {
        val station = stop.station!!
        val slug = SevStopResolver.stationSlug(station)!!
        val data = mapOf("slug" to slug,
            "location" to mapOf("latitude" to station.latitude, "longitude" to station.longitude),
            "poi" to mapOf("RAIL_REPLACEMENT_TRANSPORT" to points.map { point ->
                mapOf("type" to "Feature", "id" to point.id,
                    "geometry" to mapOf("type" to "Point", "coordinates" to listOf(point.longitude, point.latitude)),
                    "properties" to mapOf("type" to "RAIL_REPLACEMENT_TRANSPORT", "id" to point.id, "name" to point.label))
            }), "notes" to mapOf("RAIL_REPLACEMENT_TRANSPORT" to listOf(mapOf("body" to text))))
        val html = "<script>self.__next_f.push([1,${gson.toJson("27:${gson.toJson(data)}\n")}]);</script>"
        return BahnhofSevParser.parse(html, slug, clock)!!
    }

    private fun checkin(route: List<StopStation>) = CheckinInfo(hafasId = null, category = "bus", mode = "bus",
        lineName = "RE1", distanceMeters = null, points = null, duration = null,
        origin = route.first(), destination = route.last(), operator = null, trip = 1, tripUuid = "trip",
        number = null, routeColor = null, routeTextColor = null, journeyNumber = null,
        manualDeparture = null, manualArrival = null)

    private fun stop(id: Int, name: String, lat: Double, lon: Double, uuid: String, clock: Long) =
        StopStation(uuid = uuid, station = TrainStation(id = id, name = name, latitude = lat, longitude = lon),
            departurePlanned = Instant.ofEpochMilli(clock).toString())

    private fun instant(value: String) = Instant.parse(value).toEpochMilli()
}
