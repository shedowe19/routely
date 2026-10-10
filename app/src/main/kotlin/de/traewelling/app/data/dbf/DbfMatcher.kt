package de.traewelling.app.data.dbf

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Match only the current board window, operational train number and planned event. */
object DbfMatcher {
    private val zone = ZoneId.of("Europe/Berlin")
    // The repository requests past=1. Restrict matching to a nearby actual event
    // window without guessing a date for the source's date-less HH:mm fields.
    private val lookBehind = Duration.ofMinutes(60)
    private val lookAhead = Duration.ofMinutes(180)

    fun match(
        board: DbfBoard,
        eva: String,
        journeyNumber: String?,
        arrivalPlanned: Instant?,
        departurePlanned: Instant?
    ): DbfStopDelta? {
        val station = DbfJsonParser.normalizedEva(eva) ?: return null
        if (station != board.eva) return null
        val number = trainNumber(journeyNumber) ?: return null
        val rows = board.departures.filter { trainNumber(it.trainNumber) == number }
        val arrivals = rows.filter {
            matchesTime(it.scheduledArrival, arrivalPlanned, board.fetchedAt, it.delayArrival, it.missingRealtime)
        }
        val departures = rows.filter {
            matchesTime(it.scheduledDeparture, departurePlanned, board.fetchedAt, it.delayDeparture, it.missingRealtime)
        }
        if (arrivals.size > 1 || departures.size > 1) return null
        val arrivalRow = arrivals.singleOrNull()
        val departureRow = departures.singleOrNull()
        if (arrivalRow == null && departureRow == null) return null
        // Equality is not identity: equal duplicates must have been rejected above as well.
        if (arrivalRow != null && departureRow != null && arrivalRow !== departureRow) return null
        val row = arrivalRow ?: departureRow ?: return null
        val allRequestedEventsMatched = (arrivalPlanned == null || arrivalRow != null) &&
            (departurePlanned == null || departureRow != null)
        val cancelled: DbfField<Boolean> = when {
            !allRequestedEventsMatched -> DbfField.Absent
            row.isCancelled == true -> DbfField.Present(true)
            // False also includes partial cancellation; v3 cannot certify restoration.
            else -> DbfField.Absent
        }
        return DbfStopDelta(
            arrival = arrivalRow?.let {
                eventDelta(it, arrivalPlanned!!, it.delayArrival, it.scheduledDeparture == null)
            },
            departure = departureRow?.let { eventDelta(it, departurePlanned!!, it.delayDeparture, true) },
            cancelled = cancelled,
            fetchedAt = board.fetchedAt
        )
    }

    private fun matchesTime(
        time: LocalTime?, planned: Instant?, fetchedAt: Instant, delay: Int?, missingRealtime: Boolean?
    ): Boolean {
        if (time == null || planned == null) return false
        // A substantially delayed train can have a planned time outside this window,
        // but a confirmed nonzero delay brings its actual event into the current board.
        // Bound the delay to avoid matching an occurrence from another service day.
        val relevant = if (missingRealtime == false && delay != null && delay != 0 && delay in -180..720) {
            planned.plusSeconds(delay.toLong() * 60)
        } else planned
        if (relevant < fetchedAt.minus(lookBehind) || relevant > fetchedAt.plus(lookAhead)) return false
        val local = planned.atZone(zone).toLocalDateTime()
        // DBF HH:mm cannot distinguish either occurrence of the repeated autumn hour.
        if (zone.rules.getValidOffsets(local).size != 1) return false
        return local.toLocalTime().withSecond(0).withNano(0) == time
    }

    private fun eventDelta(row: DbfDeparture, planned: Instant, delay: Int?, usePlatform: Boolean): DbfEventDelta {
        if (row.missingRealtime != false || row.isCancelled == true) return DbfEventDelta()
        // DBF also calculates 0 from a planned-time fallback. Never erase a known delay
        // merely because its v3 representation cannot prove realtime punctuality.
        val actual = delay?.takeIf { it != 0 && it in -180..720 }
            ?.let { planned.plusSeconds(it.toLong() * 60) }
        val changedPlatform = if (usePlatform) changedPlatform(row) else null
        return DbfEventDelta(
            realTime = actual?.let { DbfField.Present(it) } ?: DbfField.Absent,
            // v3 collapses the event platforms into one value, preferring departure.
            platform = changedPlatform?.let { DbfField.Present(it) } ?: DbfField.Absent,
            scheduledPlatform = if (changedPlatform != null) DbfField.Present(row.scheduledPlatform)
                else DbfField.Absent
        )
    }

    private fun changedPlatform(row: DbfDeparture): String? = row.platform?.takeIf {
        row.scheduledPlatform != null && it != row.scheduledPlatform
    }

    private fun trainNumber(value: String?): Long? = value?.trim()
        ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.toLongOrNull()?.takeIf { it > 0 }
}
