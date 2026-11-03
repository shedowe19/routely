package de.traewelling.app.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepartureOperatorTest {
    private val gson = Gson()

    @Test
    fun departureReadsOperatorFromCurrentResource() {
        val json = requireNotNull(javaClass.getResourceAsStream("/traewelling/departures-current.json"))
            .bufferedReader().use { it.readText() }
        val departure = requireNotNull(gson.fromJson(json, DepartureResponse::class.java).data).single()

        assertEquals("Testbahn", departure.operatorName)
        assertEquals("81379d66-6f6a-462b-a0f0-68208b2cf9f1", departure.operator?.uuid)
    }

    @Test
    fun unknownOrMissingOperatorDoesNotInventAName() {
        for (json in listOf("""{"tripId":"test"}""", """{"tripId":"test","operator":null}""")) {
            val departure = gson.fromJson(json, DepartureTrip::class.java)
            assertNull(departure.operatorName)
        }
    }

    @Test
    fun operatorNameIgnoresBlankNamesAndTrimsProviderWhitespace() {
        val blank = gson.fromJson("""{"tripId":"test","operator":{"name":"  "}}""", DepartureTrip::class.java)
        val named = gson.fromJson("""{"tripId":"test","operator":{"name":"  Testbahn  "}}""", DepartureTrip::class.java)

        assertNull(blank.operatorName)
        assertEquals("Testbahn", named.operatorName)
    }
}
