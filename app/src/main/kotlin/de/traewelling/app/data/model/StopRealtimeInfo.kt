package de.traewelling.app.data.model

/** A successful client read is distinct from the provider's last data change. */
data class StopRealtimeInfo(
    val fetchedAtMillis: Long,
    val sourceLabel: String,
    val providerUpdatedAtMillis: Long? = null
) {
    fun isFresh(nowMillis: Long, maxAgeMillis: Long = MAX_READ_AGE_MILLIS): Boolean =
        fetchedAtMillis > 0 && maxAgeMillis >= 0 && nowMillis >= fetchedAtMillis &&
            nowMillis - fetchedAtMillis <= maxAgeMillis

    fun displayLabel(nowMillis: Long): String = when {
        isFresh(nowMillis) -> sourceLabel
        fetchedAtMillis <= 0 || nowMillis < fetchedAtMillis -> "$sourceLabel · Aktualität unbekannt"
        else -> "$sourceLabel · älterer Abruf"
    }

    companion object {
        const val MAX_READ_AGE_MILLIS = 120_000L
    }
}

/** Never stamp a retained snapshot after a failed stopover request. */
internal fun StopStation.withTraewellingReadInfo(fetchedAtMillis: Long): StopStation {
    val info = StopRealtimeInfo(fetchedAtMillis, "Träwelling")
    return copy(arrivalRealtimeInfo = info, departureRealtimeInfo = info,
        cancellationRealtimeInfo = info, arrivalPlatformRealtimeInfo = info,
        departurePlatformRealtimeInfo = info)
}

/** Legacy caches have no evidence of when their live fields were obtained. */
internal fun StopStation.withUnknownReadInfo(): StopStation {
    val unknown = StopRealtimeInfo(0, "Träwelling")
    return copy(arrivalRealtimeInfo = arrivalRealtimeInfo ?: unknown,
        departureRealtimeInfo = departureRealtimeInfo ?: unknown,
        cancellationRealtimeInfo = cancellationRealtimeInfo ?: unknown,
        arrivalPlatformRealtimeInfo = arrivalPlatformRealtimeInfo ?: unknown,
        departurePlatformRealtimeInfo = departurePlatformRealtimeInfo ?: unknown)
}
