package de.traewelling.app.data.sev

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BahnhofSevParserTest {
    private val gson = Gson()
    private val fetched = 1_791_283_000_000L

    @Test fun extractsOfficialEssenPointInsteadOfStationCenterOrOrdinaryBusStop() {
        val map = parse(essen())!!
        assertEquals("essen-hbf", map.slug)
        assertEquals("https://www.bahnhof.de/essen-hbf/karte", map.sourceUrl)
        assertEquals(51.451355, map.stationLatitude, 0.000000001)
        assertEquals(7.014793, map.stationLongitude, 0.000000001)
        assertEquals(1, map.points.size)
        assertEquals("sev.101840", map.points.single().id)
        assertEquals(51.45018831, map.points.single().latitude, 0.000000001)
        assertEquals(7.0101172, map.points.single().longitude, 0.000000001)
        assertNull(map.points.single().label)
        assertEquals("27.05.2025, 09:19", map.points.single().version)
        assertEquals(fetched, map.fetchedAtMillis)
        assertTrue(map.notes.single().contains("Kruppstraße vor DSV"))
    }

    @Test fun joinsRecordsSplitAcrossScriptChunksWithoutExecutingJavaScript() {
        val payload = "27:[\"$\",\"Map\",null,${JsonParser.parseString(essen())}]\n"
        val first = payload.take(payload.length / 2)
        val second = payload.drop(payload.length / 2)
        val html = "<script>self.__next_f.push([0]);self.__next_f.push([1,${gson.toJson(first)}]);</script>" +
            "<SCRIPT async>self.__next_f.push([1,${gson.toJson(second)}])</SCRIPT>" +
            "<script>throw new Error('must never execute');</script>"
        assertEquals("sev.101840", BahnhofSevParser.parse(html, "essen-hbf", fetched)!!.points.single().id)
    }

    @Test fun preservesDirectionSpecificDateAndFullTemporaryGuidance() {
        val data = essen().replace("essen-hbf", "muelheim-ruhr-hbf")
            .replace("\"$" + "undefined\"", "\"Richtung Duisburg \\nbis zum 09.10.2026: Richtung Oberhausen\"")
            .replace("Verlassen Sie den Bahnhof durch den Ausgang Freiheit. Kruppstraße vor DSV.",
                "Temporäre Ersatzhaltestellen vom 04.09 bis 30.10.2026:\\n\\nRichtung Duisburg; Oberhausen bis 09.10.2026.")
        val map = BahnhofSevParser.parse(page(data), "muelheim-ruhr-hbf", fetched)!!
        assertEquals("Richtung Duisburg\nbis zum 09.10.2026: Richtung Oberhausen", map.points.single().label)
        assertTrue(map.notes.single().contains("04.09 bis 30.10.2026"))
        assertTrue(map.notes.single().contains("\n\n"))
        assertTrue(map.notes.single().contains("Oberhausen bis 09.10.2026"))
    }

    @Test fun ignoresOtherStationObjectsAndRejectsWrongRequestedStation() {
        assertNull(BahnhofSevParser.parse(page(essen()), "duisburg-hbf", fetched))
        val other = essen().replace("essen-hbf", "duisburg-hbf")
        assertEquals("essen-hbf", parse("[$other,${essen()}]")!!.slug)
    }

    @Test fun identicalRepeatedMapDataIsAcceptedButConflictingSnapshotsAreRejected() {
        assertEquals(1, parse("[${essen()},${essen()}]")!!.points.size)
        val different = essen().replace("51.45018831", "51.45028831")
        assertNull(parse("[${essen()},$different]"))
    }

    @Test fun duplicateEqualPointIdsAreDeduplicatedButConflictingIdsAreRejected() {
        val repeated = essen().replace(point(), "${point()},${point()}")
        assertEquals(1, parse(repeated)!!.points.size)
        val conflicting = essen().replace(point(), "${point()},${point().replace("51.45018831", "51.45028831")}")
        assertNull(parse(conflicting))
    }

    @Test fun wrongGeometryAndUnclassifiedPointsNeverBecomeSevCoordinates() {
        assertNull(parse(essen().replace("\"Point\"", "\"LineString\"")))
        assertNull(parse(essen().replace("\"type\":\"RAIL_REPLACEMENT_TRANSPORT\"", "\"type\":\"BUS\"")))
        assertNull(parse(essen().replace("\"type\":\"Feature\"", "\"type\":\"Unknown\"")))
    }

    @Test fun rejectsMissingOrMismatchedFeatureIdentity() {
        assertNull(parse(essen().replace("\"id\":\"sev.101840\",\"properties\"", "\"properties\"")))
        assertNull(parse(essen().replace("\"properties\":{\"id\":\"sev.101840\"", "\"properties\":{\"id\":\"sev.other\"")))
    }

    @Test fun rejectsOutOfRangeNonFiniteStringAndIncorrectLengthCoordinates() {
        assertNull(parse(essen().replace("51.45018831", "91")))
        assertNull(parse(essen().replace("7.0101172", "181")))
        assertNull(parse(essen().replace("51.45018831", "1e999")))
        assertNull(parse(essen().replace("51.45018831", "\"51.45018831\"")))
        assertNull(parse(essen().replace("[7.0101172,51.45018831]", "[7.0101172,51.45018831,0]")))
    }

    @Test fun rejectsInvalidOrMissingStationCenter() {
        assertNull(parse(essen().replace("51.451355", "-91")))
        assertNull(parse(essen().replace("\"location\"", "\"otherLocation\"")))
    }

    @Test fun noSevCollectionDoesNotReclassifyRegularPublicTransport() {
        val data = """{"slug":"essen-hbf","location":{"longitude":7.014793,"latitude":51.451355},
            "poi":{"BUS":[${point().replace("RAIL_REPLACEMENT_TRANSPORT", "BUS")}]} }"""
        assertTrue(parse(data)!!.points.isEmpty())
        assertTrue(parse(data)!!.notes.isEmpty())
    }

    @Test fun malformedPageOrExecutablePushExpressionIsUnavailable() {
        assertNull(BahnhofSevParser.parse("<script>self.__next_f.push([1, loadMap()])</script>", "essen-hbf", fetched))
        assertNull(BahnhofSevParser.parse("<script>self.__next_f.push([1,\"27:{broken\"])</script>", "essen-hbf", fetched))
        assertNull(BahnhofSevParser.parse("<html>Service unavailable</html>", "essen-hbf", fetched))
    }

    @Test fun validatesSlugWithoutTurningItIntoAnotherUrl() {
        assertFalse(BahnhofSevParser.isValidSlug("../essen-hbf"))
        assertFalse(BahnhofSevParser.isValidSlug("https://other.example"))
        assertFalse(BahnhofSevParser.isValidSlug("essen-hbf?redirect=other"))
        assertFalse(BahnhofSevParser.isValidSlug("a".repeat(121)))
        assertTrue(BahnhofSevParser.isValidSlug("muelheim-ruhr-hbf"))
        assertNull(BahnhofSevParser.parse(page(essen()), "../essen-hbf", fetched))
    }

    @Test fun changedCollectionShapeIsRejectedAndNegativeFetchTimeIsUnavailable() {
        assertNull(parse(essen().replace("\"RAIL_REPLACEMENT_TRANSPORT\":[${point()}]", "\"RAIL_REPLACEMENT_TRANSPORT\":{}")))
        assertNull(BahnhofSevParser.parse(page(essen()), "essen-hbf", -1))
    }

    private fun parse(data: String) = BahnhofSevParser.parse(page(data), "essen-hbf", fetched)

    private fun page(data: String): String = "<script>self.__next_f.push([1,${gson.toJson("27:${JsonParser.parseString(data)}\n")}]);</script>"

    // Minimal public Essen map data observed on 06.10.2026. Ordinary BUS deliberately differs.
    private fun essen(): String = """{"slug":"essen-hbf","location":{"longitude":7.014793,"latitude":51.451355},
        "poi":{"BUS":[{"type":"Feature","geometry":{"type":"Point","coordinates":[7.02,51.46]},"properties":{"type":"BUS"}}],
        "RAIL_REPLACEMENT_TRANSPORT":[${point()}]},
        "notes":{"RAIL_REPLACEMENT_TRANSPORT":[{"body":"Verlassen Sie den Bahnhof durch den Ausgang Freiheit. Kruppstraße vor DSV."}]}}"""

    private fun point(): String = """{"type":"Feature","geometry":{"type":"Point","coordinates":[7.0101172,51.45018831]},
        "id":"sev.101840","properties":{"id":"sev.101840","type":"RAIL_REPLACEMENT_TRANSPORT",
        "name":"${'$'}undefined","version":"27.05.2025, 09:19","level":"GROUND_FLOOR"}}"""
}
