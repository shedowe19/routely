package de.traewelling.app.ui.screens

import de.traewelling.app.data.model.DepartureTrip
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class DepartureTimePresentationTest {
    private val departure = DepartureTrip("trip", null, null,
        "2026-10-06T18:00:00Z", "2026-10-06T17:57:00Z", null, null, false)

    @Test fun earlyDepartureShowsTheRealTimePlannedReferenceAndNegativeDeviation() {
        val presentation = departureTimePresentation(departure, ZoneOffset.UTC)
        assertEquals("17:57", presentation.time)
        assertEquals("18:00", presentation.plannedTime)
        assertEquals("-3 min", presentation.deviation)
        assertTrue(presentation.earlier)
        assertFalse(presentation.delayed)
    }

    @Test fun lateDepartureShowsRealTimeInsteadOfOnlyAddingMinutesToThePlan() {
        val presentation = departureTimePresentation(departure.copy(realWhen = "2026-10-06T18:05:00Z"), ZoneOffset.UTC)
        assertEquals("18:05", presentation.time)
        assertEquals("18:00", presentation.plannedTime)
        assertEquals("+5 min", presentation.deviation)
        assertTrue(presentation.delayed)
    }

    @Test fun absentOrMalformedRealtimeFallsBackToThePlanWithoutInventedDeviation() {
        for (real in listOf(null, "invalid")) {
            val presentation = departureTimePresentation(departure.copy(realWhen = real), ZoneOffset.UTC)
            assertEquals("18:00", presentation.time)
            assertNull(presentation.plannedTime)
            assertNull(presentation.deviation)
        }
    }

    @Test fun equivalentOffsetsAreOnTimeAndRenderInTheSelectedLocalZone() {
        val presentation = departureTimePresentation(departure.copy(realWhen = "2026-10-06T20:00:00+02:00"), ZoneId.of("Europe/Berlin"))
        assertEquals("20:00", presentation.time)
        assertNull(presentation.plannedTime)
        assertNull(presentation.deviation)
    }

    @Test fun usableRealtimeWithoutPlanIsDisplayedWithoutAnInventedPlannedReference() {
        val presentation = departureTimePresentation(departure.copy(plannedWhen = null), ZoneOffset.UTC)
        assertEquals("17:57", presentation.time)
        assertNull(presentation.plannedTime)
        assertNull(presentation.deviation)
    }

    @Test fun missingTimesHaveAnExplicitPlaceholder() {
        assertEquals("–", departureTimePresentation(null, ZoneOffset.UTC).time)
    }
}
