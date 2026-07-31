package de.traewelling.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.GET

class TraewellingApiServiceTest {

    @Test
    fun departureEndpointUsesStationRouteWithoutTrainsPrefix() {
        val method = TraewellingApiService::class.java.declaredMethods.single {
            it.name == "getStationDepartures"
        }

        assertEquals(
            "api/v1/station/{id}/departures",
            requireNotNull(method.getAnnotation(GET::class.java)).value
        )
    }
}
