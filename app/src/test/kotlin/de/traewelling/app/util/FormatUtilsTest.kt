package de.traewelling.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FormatUtilsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 6, 23, 0, 0, 0, zone)

    @Test
    fun `just now`() {
        assertEquals(
            "gerade eben",
            formatRelativeTimestamp("2026-11-06T22:59:40+01:00", now)
        )
    }

    @Test
    fun `minutes ago`() {
        assertEquals(
            "vor 5 Min",
            formatRelativeTimestamp("2026-11-06T22:55:00+01:00", now)
        )
    }

    @Test
    fun `hours ago`() {
        assertEquals(
            "vor 2 Std",
            formatRelativeTimestamp("2026-11-06T21:00:00+01:00", now)
        )
    }

    @Test
    fun `yesterday keeps the clock time`() {
        assertEquals(
            "gestern, 22:00",
            formatRelativeTimestamp("2026-11-05T22:00:00+01:00", now)
        )
    }

    @Test
    fun `older dates use the absolute day`() {
        assertEquals(
            "01.11., 08:30",
            formatRelativeTimestamp("2026-11-01T08:30:00+01:00", now)
        )
    }

    @Test
    fun `unparseable values fall back to the raw timestamp`() {
        assertEquals(
            "kein Datum",
            formatRelativeTimestamp("kein Datum", now)
        )
    }
}
