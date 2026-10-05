package de.traewelling.app.ui.screens

import de.traewelling.app.data.model.StopStation
import de.traewelling.app.service.TrackingLiveState
import de.traewelling.app.service.TrackingSource
import java.time.Instant

internal enum class TimelinePositionSource { GPS, TIMETABLE, WAITING }

/** One visit cursor drives every row and both halves of each timeline segment. */
internal data class StopTimelineProgress(
    val currentIndex: Int? = null,
    val nextIndex: Int? = null,
    val passedThroughIndex: Int = -1,
    val source: TimelinePositionSource = TimelinePositionSource.TIMETABLE
) {
    fun badgeFor(index: Int): String? = when {
        index == currentIndex -> if (source == TimelinePositionSource.GPS) "AKTUELL" else "LAUT FAHRPLAN"
        index == nextIndex -> if (source == TimelinePositionSource.GPS) "ALS NÄCHSTES" else "NÄCHSTER HALT · CA."
        else -> null
    }
}

internal fun resolveStopTimelineProgress(
    stops: List<StopStation>,
    nowMillis: Long,
    tracking: TrackingLiveState?,
    destinationIndex: Int = -1
): StopTimelineProgress {
    if (tracking != null) {
        // The tracker covers only the checked-in section. Its integer cursor is
        // therefore not an index into this full trip; match the visit instead.
        val visitIndex = stops.indexOfFirst { it.matchesStopover(tracking.stop) }
            .takeIf { it >= 0 }
            ?: stops.indexOfFirst { it.uuid != null && it.uuid == tracking.nextStopKey }
                .takeIf { it >= 0 }
        val source = if (tracking.source == TrackingSource.GPS) TimelinePositionSource.GPS
            else TimelinePositionSource.TIMETABLE

        if (tracking.completed) {
            return StopTimelineProgress(
                passedThroughIndex = visitIndex ?: destinationIndex.takeIf { it in stops.indices } ?: -1,
                source = source
            )
        }
        // A stale or temporarily unavailable route match must not replace an
        // established GPS cursor with the clock, especially on early journeys.
        if (visitIndex == null || stops[visitIndex].cancelled == true) {
            return StopTimelineProgress(source = TimelinePositionSource.WAITING)
        }
        return if (tracking.arrivedAtCurrent) {
            StopTimelineProgress(currentIndex = visitIndex, passedThroughIndex = visitIndex, source = source)
        } else {
            StopTimelineProgress(nextIndex = visitIndex, passedThroughIndex = visitIndex - 1, source = source)
        }
    }

    val visits = stops.mapIndexedNotNull { index, stop ->
        if (stop.cancelled == true) return@mapIndexedNotNull null
        val arrival = stop.effectiveArrival.epochMillis() ?: stop.effectiveDeparture.epochMillis()
        val departure = stop.effectiveDeparture.epochMillis() ?: arrival
        arrival?.let { TimedVisit(index, it, maxOf(it, departure ?: it)) }
    }
    // Unlike independent +/-1 minute row windows, this produces a single
    // selection even for consecutive bus stops or overlapping API times.
    val current = visits.lastOrNull { nowMillis in it.arrivalMillis..it.departureMillis }
    if (current != null) {
        return StopTimelineProgress(currentIndex = current.index, passedThroughIndex = current.index)
    }
    val next = visits.firstOrNull { it.arrivalMillis > nowMillis }
    val passed = visits.lastOrNull { it.departureMillis < nowMillis }?.index ?: -1
    return StopTimelineProgress(nextIndex = next?.index, passedThroughIndex = passed)
}

private data class TimedVisit(val index: Int, val arrivalMillis: Long, val departureMillis: Long)

private fun String?.epochMillis(): Long? = this?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
}
