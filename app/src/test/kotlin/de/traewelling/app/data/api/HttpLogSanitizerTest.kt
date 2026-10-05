package de.traewelling.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HttpLogSanitizerTest {
    @Test fun nearbyCoordinatesAreRedactedInRequestAndResponseLines() {
        val request = "--> GET https://example.test/api/v1/stations?min_lat=51.231&max_lat=51.243&min_lon=6.612&max_lon=6.634"
        val redacted = HttpLogSanitizer.sanitize(request)
        listOf("51.231", "51.243", "6.612", "6.634").forEach { assertFalse(redacted.contains(it)) }
        assertEquals(4, Regex("\\[redacted]").findAll(redacted).count())
        assertEquals("<-- 200 https://example.test/nearby?latitude=[redacted]&longitude=[redacted] (20ms)",
            HttpLogSanitizer.sanitize("<-- 200 https://example.test/nearby?latitude=51.23&longitude=6.62 (20ms)"))
    }

    @Test fun nonLocationQueriesArePreserved() {
        val message = "--> GET https://example.test/api/v1/station/42/departures?when=2026-10-05T19%3A00%3A00Z"
        assertEquals(message, HttpLogSanitizer.sanitize(message))
    }
}
