package de.traewelling.app.service

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.StopStation
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

internal enum class TripChangeKind { PLATFORM, CANCELLED, RESTORED, DELAY_INCREASE, DELAY_DECREASE }

internal data class TripChangeEvent(
    val key: String,
    val kind: TripChangeKind,
    val stopKey: String,
    val title: String,
    val message: String
)

/** Only deduplication metadata is cached. A cached route never becomes an alert baseline. */
internal data class TripChangeMonitorState(
    val statusId: Int,
    val lastEvents: Map<String, String> = emptyMap()
)

internal data class TripChangeStop(
    val key: String,
    val name: String,
    val arrivalPlannedMillis: Long? = null,
    val arrivalRealMillis: Long? = null,
    val departurePlannedMillis: Long? = null,
    val departureRealMillis: Long? = null,
    val arrivalPlatform: String? = null,
    val departurePlatform: String? = null,
    val cancelled: Boolean? = null,
    val isOrigin: Boolean = false,
    val isDestination: Boolean = false,
    val arrivalPlatformIsLive: Boolean? = null,
    val departurePlatformIsLive: Boolean? = null
)

internal data class TripChangeSnapshot(
    val statusId: Int,
    val observedAtMillis: Long,
    val stops: List<TripChangeStop>,
    val nextIndex: Int,
    val manualDepartureMillis: Long? = null,
    val manualArrivalMillis: Long? = null
) {
    companion object {
        /** Read raw provider times; manual check-in overrides do not masquerade as live data. */
        fun fromApi(
            statusId: Int,
            observedAtMillis: Long,
            stops: List<StopStation>,
            checkin: CheckinInfo,
            nextIndex: Int
        ): TripChangeSnapshot = TripChangeSnapshot(
            statusId = statusId,
            observedAtMillis = observedAtMillis,
            stops = stops.mapIndexed { index, stop ->
                val arrival = epochMillis(stop.arrivalPlanned)
                val departure = epochMillis(stop.departurePlanned)
                val stationKey = stop.station?.uuid ?: stop.stationId?.toString()
                val key = stop.uuid?.takeIf(String::isNotBlank) ?: stationKey
                    ?.takeIf { arrival != null || departure != null }
                    ?.let { "$it:$arrival:$departure" }
                    ?: return@mapIndexed TripChangeStop("unresolved:$index", stop.stationName ?: "Unbekannte Station")
                TripChangeStop(
                    key = key,
                    name = stop.stationName ?: "Unbekannte Station",
                    arrivalPlannedMillis = arrival,
                    arrivalRealMillis = epochMillis(stop.arrivalReal),
                    departurePlannedMillis = departure,
                    departureRealMillis = epochMillis(stop.departureReal),
                    arrivalPlatform = cleanPlatform(stop.arrivalPlatformReal) ?: cleanPlatform(stop.arrivalPlatformPlanned) ?: cleanPlatform(stop.platform),
                    departurePlatform = cleanPlatform(stop.departurePlatformReal) ?: cleanPlatform(stop.departurePlatformPlanned) ?: cleanPlatform(stop.platform),
                    cancelled = stop.cancelled,
                    isOrigin = stop.matchesStopover(checkin.origin),
                    isDestination = stop.matchesStopover(checkin.destination),
                    arrivalPlatformIsLive = when {
                        cleanPlatform(stop.arrivalPlatformReal) != null -> true
                        cleanPlatform(stop.arrivalPlatformPlanned) != null -> false
                        else -> null
                    },
                    departurePlatformIsLive = when {
                        cleanPlatform(stop.departurePlatformReal) != null -> true
                        cleanPlatform(stop.departurePlatformPlanned) != null -> false
                        else -> null
                    }
                )
            },
            nextIndex = nextIndex,
            manualDepartureMillis = epochMillis(checkin.manualDeparture),
            manualArrivalMillis = epochMillis(checkin.manualArrival)
        )

        private fun epochMillis(value: String?): Long? = value?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }

        private fun cleanPlatform(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)
    }
}

/** Compares consecutive successful API loads, independently of location/timetable ticking. */
internal class TripChangeMonitor(private val delayThresholdMinutes: Int = 5) {
    private var statusId: Int? = null
    private var baseline: TripChangeSnapshot? = null
    private val lastEvents = linkedMapOf<String, String>()
    private val delayReferences = mutableMapOf<String, DelayObservation>()
    private val platformLiveGaps = mutableSetOf<String>()

    private data class DelayObservation(val plannedMillis: Long, val minutes: Int, val arrival: Boolean)

    fun reset(statusId: Int, state: TripChangeMonitorState? = null) {
        this.statusId = statusId
        baseline = null
        delayReferences.clear()
        platformLiveGaps.clear()
        lastEvents.clear()
        if (state?.statusId == statusId) lastEvents.putAll(state.lastEvents)
    }

    fun getState(): TripChangeMonitorState? = statusId?.let { TripChangeMonitorState(it, lastEvents.toMap()) }

    fun observe(snapshot: TripChangeSnapshot): List<TripChangeEvent> {
        if (statusId != snapshot.statusId) reset(snapshot.statusId)
        if (snapshot.stops.isEmpty() || snapshot.stops.map { it.key }.distinct().size != snapshot.stops.size) return emptyList()
        val previous = baseline
        if (previous != null && snapshot.observedAtMillis <= previous.observedAtMillis) return emptyList()
        baseline = snapshot
        if (previous == null) {
            snapshot.stops.forEach { stop -> delayObservation(stop)?.let { delayReferences[stop.key] = it } }
            synchronizeValues(snapshot)
            return emptyList()
        }

        val previousByKey = previous.stops.associateBy { it.key }
        val remaining = snapshot.stops.drop(snapshot.nextIndex.coerceIn(0, snapshot.stops.size))
        val nextServedStopKey = remaining.firstOrNull { it.cancelled != true }?.key
        val events = mutableListOf<TripChangeEvent>()
        for ((index, stop) in remaining.withIndex()) {
            val old = previousByKey[stop.key] ?: continue
            if (old.cancelled != null && stop.cancelled != null && old.cancelled != stop.cancelled) {
                val cancelled = stop.cancelled
                val subject = when {
                    stop.isDestination -> "Deine Ausstiegshaltestelle ${stop.name}"
                    stop.isOrigin -> "Deine Einstiegshaltestelle ${stop.name}"
                    index == 0 -> "Der nächste Halt ${stop.name}"
                    else -> "Der Halt ${stop.name}"
                }
                emit(events, stop, "cancelled", cancelled.toString(),
                    if (cancelled) TripChangeKind.CANCELLED else TripChangeKind.RESTORED,
                    if (cancelled) "Halt entfällt" else "Halt wieder vorgesehen",
                    if (cancelled) "$subject entfällt laut aktueller Meldung. Prüfe deine Weiterreise."
                    else "$subject ist laut aktueller Meldung wieder vorgesehen.")
            }
            // Future intermediate platforms are noisy; the next stop and exit need action now.
            if (stop.cancelled != true && (stop.key == nextServedStopKey || stop.isDestination)) {
                val oldPlatform = platform(old)
                val newPlatform = platform(stop)
                val oldIsLive = platformIsLive(old)
                val newIsLive = platformIsLive(stop)
                val liveValueLost = oldIsLive == true && newIsLive != true
                if (liveValueLost) platformLiveGaps += stop.key
                val liveValueRestored = newIsLive == true && platformLiveGaps.remove(stop.key)
                if (!liveValueLost && !liveValueRestored && oldPlatform != null && newPlatform != null &&
                    !oldPlatform.equals(newPlatform, ignoreCase = true)
                ) {
                    emit(events, stop, "platform", newPlatform.uppercase(Locale.ROOT), TripChangeKind.PLATFORM,
                        "Gleiswechsel in ${stop.name}",
                        "${stop.name}: Gleis $newPlatform statt Gleis $oldPlatform.")
                }
            }
        }

        // Prefer the arrival at the user's destination; fall back to the next known live time.
        val delayStop = remaining.filter { it.cancelled != true }
            .sortedByDescending { it.isDestination }
            .firstOrNull { delayObservation(it) != null && previousByKey[it.key]?.let(::delayObservation) != null }
        if (delayStop != null) {
            val old = previousByKey.getValue(delayStop.key)
            val current = delayObservation(delayStop)!!
            val prior = delayObservation(old)!!
            val manualChanged = (delayStop.isOrigin && !current.arrival && previous.manualDepartureMillis != snapshot.manualDepartureMillis) ||
                (delayStop.isDestination && current.arrival && previous.manualArrivalMillis != snapshot.manualArrivalMillis)
            val reference = delayReferences[delayStop.key]
            if (manualChanged || prior.plannedMillis != current.plannedMillis || prior.arrival != current.arrival ||
                reference == null || reference.plannedMillis != current.plannedMillis || reference.arrival != current.arrival
            ) {
                delayReferences[delayStop.key] = current
            } else if (abs(current.minutes - reference.minutes) >= delayThresholdMinutes) {
                val increased = current.minutes > reference.minutes
                val timeDescription = when {
                    current.minutes > 0 -> "${current.minutes} ${if (current.minutes == 1) "Minute" else "Minuten"} Verspätung"
                    current.minutes < 0 -> "${-current.minutes} ${if (current.minutes == -1) "Minute" else "Minuten"} vor dem Fahrplan"
                    else -> "pünktlich"
                }
                val title = when {
                    increased && current.minutes > 0 -> "Mehr Verspätung"
                    !increased && reference.minutes > 0 -> "Verspätung verringert"
                    else -> "${if (current.arrival) "Ankunft" else "Abfahrt"} ${if (increased) "später" else "früher"}"
                }
                emit(events, delayStop, "delay", current.minutes.toString(),
                    if (increased) TripChangeKind.DELAY_INCREASE else TripChangeKind.DELAY_DECREASE,
                    title,
                    "${delayStop.name}: jetzt $timeDescription; " +
                        "${abs(current.minutes - reference.minutes)} Minuten ${if (increased) "später" else "früher"} als zuletzt gemeldet.")
                delayReferences[delayStop.key] = current
            }
        }

        // A missing live field breaks continuity rather than causing a null-to-value alert later.
        snapshot.stops.forEach { stop ->
            val observation = delayObservation(stop)
            if (observation == null) delayReferences.remove(stop.key)
            else if (stop.key !in delayReferences || stop.key != delayStop?.key) delayReferences[stop.key] = observation
        }
        synchronizeValues(snapshot)
        return events
    }

    /** Fresh or re-established baselines must not retain an unrelated old dedup value. */
    private fun synchronizeValues(snapshot: TripChangeSnapshot) {
        for (stop in snapshot.stops) {
            val fields = mapOf("platform" to platform(stop)?.uppercase(Locale.ROOT),
                "cancelled" to stop.cancelled?.toString(), "delay" to delayObservation(stop)?.minutes?.toString())
            for ((field, value) in fields) {
                val key = "${stop.key}:$field"
                if (value == null) lastEvents.remove(key) else lastEvents[key] = value
            }
        }
        while (lastEvents.size > 96) lastEvents.remove(lastEvents.keys.first())
    }

    private fun platform(stop: TripChangeStop): String? =
        (if (stop.isOrigin) stop.departurePlatform else stop.arrivalPlatform)?.trim()?.takeIf(String::isNotEmpty)

    private fun platformIsLive(stop: TripChangeStop): Boolean? =
        if (stop.isOrigin) stop.departurePlatformIsLive else stop.arrivalPlatformIsLive

    private fun delayObservation(stop: TripChangeStop): DelayObservation? {
        val arrival = !stop.isOrigin && stop.arrivalPlannedMillis != null && stop.arrivalRealMillis != null
        val planned = if (arrival) stop.arrivalPlannedMillis else stop.departurePlannedMillis
        val real = if (arrival) stop.arrivalRealMillis else stop.departureRealMillis
        if (planned == null || real == null) return null
        return DelayObservation(planned, ((real - planned) / 60_000L).toInt(), arrival)
    }

    private fun emit(
        events: MutableList<TripChangeEvent>, stop: TripChangeStop, field: String, value: String,
        kind: TripChangeKind, title: String, message: String
    ) {
        val fieldKey = "${stop.key}:$field"
        if (lastEvents[fieldKey] == value) return
        lastEvents[fieldKey] = value
        while (lastEvents.size > 96) lastEvents.remove(lastEvents.keys.first())
        events += TripChangeEvent("$fieldKey:$value", kind, stop.key, title, message)
    }
}
