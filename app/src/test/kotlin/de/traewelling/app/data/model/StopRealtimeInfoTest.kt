package de.traewelling.app.data.model

import org.junit.Assert.*
import org.junit.Test

class StopRealtimeInfoTest {
    @Test fun recentReadDoesNotInventAProviderTimestamp() {
        val info = StopRealtimeInfo(1_000, "DBF · IRIS")
        assertTrue(info.isFresh(121_000))
        assertEquals("DBF · IRIS", info.displayLabel(121_000))
        assertNull(info.providerUpdatedAtMillis)
    }

    @Test fun expiredReadKeepsItsSourceButIsMarkedOlder() {
        val info = StopRealtimeInfo(1_000, "DBF · IRIS")
        assertFalse(info.isFresh(121_001))
        assertEquals("DBF · IRIS · älterer Abruf", info.displayLabel(121_001))
    }

    @Test fun missingOrFutureReadTimeCannotProveFreshness() {
        assertEquals("Träwelling · Aktualität unbekannt", StopRealtimeInfo(0, "Träwelling").displayLabel(1_000))
        assertEquals("DBF · IRIS · Aktualität unbekannt", StopRealtimeInfo(1_001, "DBF · IRIS").displayLabel(1_000))
    }

    @Test fun successfulTraewellingReadLabelsBothEventsAndCancellation() {
        val stop = StopStation(arrivalReal = "2026-10-10T17:00:00Z").withTraewellingReadInfo(1_000)
        assertEquals(StopRealtimeInfo(1_000, "Träwelling"), stop.arrivalRealtimeInfo)
        assertEquals(stop.arrivalRealtimeInfo, stop.departureRealtimeInfo)
        assertEquals(stop.arrivalRealtimeInfo, stop.cancellationRealtimeInfo)
    }

    @Test fun legacyCacheRemainsUnknownWithoutChangingTheValues() {
        val original = StopStation(arrivalReal = "2026-10-10T17:00:00Z", arrivalPlatformReal = "7")
        val restored = original.withUnknownReadInfo()
        assertEquals(original.arrivalReal, restored.arrivalReal)
        assertEquals(original.arrivalPlatformReal, restored.arrivalPlatformReal)
        assertFalse(restored.arrivalRealtimeInfo!!.isFresh(1_000))
    }

    @Test fun cacheRestorePreservesOriginalReadTimes() {
        val info = StopRealtimeInfo(1_000, "DBF · IRIS")
        val restored = StopStation(arrivalRealtimeInfo = info).withUnknownReadInfo()
        assertEquals(info, restored.arrivalRealtimeInfo)
        assertEquals(0L, restored.departureRealtimeInfo!!.fetchedAtMillis)
    }
}
