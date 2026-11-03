package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.CheckInResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.time.Instant

internal data class CheckInTrackingCompletion(val active: Boolean, val notice: String?)

/** Decide only from the final status, after any manual-time PUT, never from the initial POST alone. */
internal suspend fun completeCheckInTracking(
    result: CheckInResult?,
    nowMillis: Long,
    activationTimeoutMillis: Long = 15_000L,
    activateIfAvailable: suspend (Int) -> Boolean
): CheckInTrackingCompletion {
    val status = result?.status?.takeIf { it.id > 0 } ?: return CheckInTrackingCompletion(false, null)
    val checkin = status.checkin
    val arrival = sequenceOf(checkin?.manualArrival, checkin?.destination?.arrivalReal,
        checkin?.destination?.arrivalPlanned).mapNotNull { value ->
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }.firstOrNull()
    if (arrival == null) return CheckInTrackingCompletion(false,
        "Die Fahrt wurde gespeichert. Ohne gültige Ankunftszeit wurde keine Reisebegleitung gestartet.")
    if (arrival <= nowMillis) return CheckInTrackingCompletion(false,
        "Die abgeschlossene Fahrt ist gespeichert. Deine Reisebegleitung bleibt unverändert.")
    return try {
        if (withTimeout(activationTimeoutMillis) { activateIfAvailable(status.id) })
            CheckInTrackingCompletion(true, "Die Reisebegleitung ist aktiviert.")
        else CheckInTrackingCompletion(false,
            "Die Fahrt wurde gespeichert. Deine bestehende Reisebegleitung bleibt unverändert.")
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        CheckInTrackingCompletion(false,
            "Die Fahrt wurde gespeichert. Die Aktivierung der Reisebegleitung dauert zu lange; prüfe sie im Profil.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CheckInTrackingCompletion(false,
            "Die Fahrt wurde gespeichert, aber die Reisebegleitung konnte nicht aktiviert werden. Öffne die Fahrt im Profil.")
    }
}
