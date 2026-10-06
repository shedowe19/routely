package de.traewelling.app.data.api

import org.junit.Assert.*
import org.junit.Test

class ApiServerUrlTest {
    @Test fun normalizesHttpsServersAndPreservesInstancePath() {
        assertEquals("https://example.test", ApiServerUrl.normalize(" https://EXAMPLE.test/ "))
        assertEquals("https://example.test/traewelling", ApiServerUrl.normalize("https://example.test/traewelling/"))
    }

    @Test fun rejectsMissingSchemePlaintextCredentialsAndQueryWithoutEchoingInput() {
        val inputs = listOf("traewelling.de", "http://example.test", "https://private:secret@example.test/",
            "https://example.test/?access_token=secret", "https://example.test/#secret", "not a URL")
        inputs.forEach { input ->
            val error = runCatching { ApiServerUrl.normalize(input) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(requireNotNull(error?.message).contains("secret"))
        }
    }
}
