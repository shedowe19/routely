package de.traewelling.app.data.dbf

import java.time.Instant
import java.time.LocalTime

/** A missing or null source field must never manufacture a punctual prediction. */
sealed interface DbfField<out T> {
    data object Absent : DbfField<Nothing>
    data class Present<T>(val value: T?) : DbfField<T>
}

data class DbfMessage(val text: String, val timestamp: String?)

data class DbfDeparture(
    val trainNumber: String?,
    val scheduledArrival: LocalTime?,
    val scheduledDeparture: LocalTime?,
    val delayArrival: Int?,
    val delayDeparture: Int?,
    val platform: String?,
    val scheduledPlatform: String?,
    val isCancelled: Boolean?,
    val missingRealtime: Boolean?,
    val messages: List<DbfMessage> = emptyList()
)

/**
 * DBF v3 supplies neither station/date identifiers nor a prediction update timestamp.
 * eva is the exact requested station, with related stations disabled in the request.
 * fetchedAt records retrieval only, and is not the provider's update time.
 */
data class DbfBoard(
    val eva: String,
    val departures: List<DbfDeparture>,
    val fetchedAt: Instant,
    val providerUpdatedAt: Instant? = null
)

data class DbfEventDelta(
    val realTime: DbfField<Instant> = DbfField.Absent,
    val platform: DbfField<String> = DbfField.Absent,
    // Apply together with platform so the UI compares the same provider's pair.
    val scheduledPlatform: DbfField<String> = DbfField.Absent,
    val providerUpdatedAt: Instant? = null
)

data class DbfStopDelta(
    val arrival: DbfEventDelta?,
    val departure: DbfEventDelta?,
    val cancelled: DbfField<Boolean>,
    val fetchedAt: Instant,
    val providerUpdatedAt: Instant? = null
)
