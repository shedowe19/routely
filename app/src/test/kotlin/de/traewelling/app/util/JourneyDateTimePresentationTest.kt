package de.traewelling.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class JourneyDateTimePresentationTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test fun formattedValueShowsLocalDateAcrossMidnightWithoutIsoSyntax() {
        assertEquals("04.11.2026 · 00:05", journeyDateTimeLabel("2026-11-03T23:05:00Z", berlin, true, Locale.GERMANY))
    }

    @Test fun twelveHourPresentationUsesDeviceStyleAndLocale() {
        assertEquals("03.11.2026 · 1:05 PM", journeyDateTimeLabel("2026-11-03T12:05:00Z", berlin, false, Locale.US))
    }

    @Test fun invalidOrUnsetValueNeverLeaksRawDateText() {
        assertNull(journeyDateTimeLabel("broken-token-like-input", berlin, true, Locale.GERMANY))
        assertNull(journeyDateTimeInstant(""))
        assertNull(journeyDateTimeInstant(null))
    }

    @Test fun displayedZoneNamesTheActualOffsetForTheChosenDate() {
        assertEquals("Europe/Berlin (UTC+01:00)", journeyDateTimeZoneLabel("2026-11-03T12:00:00Z", berlin))
        assertEquals("Europe/Berlin (UTC+02:00)", journeyDateTimeZoneLabel("2026-07-03T12:00:00Z", berlin))
        assertEquals("UTC (UTC+00:00)", journeyDateTimeZoneLabel("2026-07-03T12:00:00Z", ZoneId.of("UTC")))
    }

    @Test fun selectedLocalTimeProducesUtcRequestAtMinutePrecision() {
        assertEquals("2026-11-03T12:05:00Z", journeyDateTimeSelection(LocalDate.of(2026, 11, 3), 13, 5, berlin, ""))
    }

    @Test fun missingSpringClockTimeIsRejectedRatherThanShifted() {
        val failure = runCatching { journeyDateTimeSelection(LocalDate.of(2026, 3, 29), 2, 30, berlin, "") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Zeitumstellung"))
    }

    @Test fun newRepeatedAutumnClockChoiceUsesEarlierOccurrence() {
        assertEquals("2026-10-25T00:30:00Z", journeyDateTimeSelection(LocalDate.of(2026, 10, 25), 2, 30, berlin, ""))
    }

    @Test fun unchangedRepeatedClockTimePreservesExistingLaterOccurrence() {
        assertEquals("2026-10-25T01:30:00Z", journeyDateTimeSelection(LocalDate.of(2026, 10, 25), 2, 30, berlin,
            "2026-10-25T02:30:42+01:00"))
    }

    @Test fun oldOffsetCannotChooseAnotherNewRepeatedClockTime() {
        assertEquals("2026-10-25T00:45:00Z", journeyDateTimeSelection(LocalDate.of(2026, 10, 25), 2, 45, berlin,
            "2026-10-25T02:30:00+01:00"))
    }

    @Test fun explicitOffsetAndUtcValuesHaveSameInstant() {
        assertEquals(journeyDateTimeInstant("2026-11-03T12:05:00Z"), journeyDateTimeInstant("2026-11-03T13:05:00+01:00"))
    }
}
