package de.traewelling.app.data.dbf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class DbfJsonParserTest {
    private val fetched = Instant.parse("2026-10-10T17:26:55Z")

    @Test fun readsCapturedPublicIrisV3WithoutInventingStationDateOrUpdateTime() {
        val board = DbfJsonParser.parse(fixture(), "8000152", fetched)
        assertEquals("8000152", board.eva)
        assertEquals(fetched, board.fetchedAt)
        assertNull(board.providerUpdatedAt)
        val arrival = board.departures.first()
        assertEquals("34328", arrival.trainNumber)
        assertEquals(LocalTime.of(19, 24), arrival.scheduledArrival)
        assertNull(arrival.scheduledDeparture)
        assertEquals(3, arrival.delayArrival)
        assertNull(arrival.delayDeparture)
        assertFalse(arrival.isCancelled!!)
        assertFalse(arrival.missingRealtime!!)
        assertTrue(arrival.messages.isNotEmpty())
    }

    @Test fun nullDelayAndMissingRealtimeStayUnknown() {
        val board = DbfJsonParser.parse(row("\"delayArrival\":null", "\"missingRealtime\":true"), "8000152", fetched)
        assertNull(board.departures.single().delayArrival)
        assertTrue(board.departures.single().missingRealtime!!)
    }

    @Test fun invalidFieldTypesDoNotBecomeValidPredictionsOrCancellation() {
        val board = DbfJsonParser.parse(row("\"delayArrival\":\"5\"", "\"isCancelled\":\"true\""), "8000152", fetched)
        assertNull(board.departures.single().delayArrival)
        assertNull(board.departures.single().isCancelled)
    }

    @Test fun rejectsErrorOtherBackendAndMalformedOrTrailingData() {
        listOf(
            "{\"error\":\"backend failed\",\"departures\":[]}",
            "{\"departures\":[{\"scheduledTime\":1791653000,\"trainNumber\":\"123\"}]}",
            "{\"departures\":", "<html>unavailable</html>",
            "{\"departures\":[]} {\"other\":1}", "{departures:[]}"
        ).forEach { json -> assertTrue(json, runCatching { DbfJsonParser.parse(json, "8000152", fetched) }.isFailure) }
    }

    @Test fun refusesInvalidEvaAndEmptyBoardIsSuccessful() {
        listOf("Hannover Hbf", "0", "8000152/../other", "0000000").forEach { eva ->
            assertTrue(runCatching { DbfJsonParser.parse("{\"departures\":[]}", eva, fetched) }.isFailure)
        }
        assertTrue(DbfJsonParser.parse("{\"departures\":[]}", "8000152", fetched).departures.isEmpty())
    }

    private fun fixture() = requireNotNull(javaClass.getResource("/dbf/public-hannover-v3.json")).readText()

    private fun row(vararg fields: String): String {
        val defaults = linkedMapOf(
            "scheduledArrival" to "\"19:24\"", "scheduledDeparture" to "null",
            "delayArrival" to "3", "delayDeparture" to "null", "trainNumber" to "\"34328\""
        )
        fields.forEach { field ->
            val key = field.substringBefore(':').trim('"')
            defaults[key] = field.substringAfter(':')
        }
        return "{\"departures\":[{" + defaults.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}]}"
    }
}
