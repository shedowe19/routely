package de.traewelling.app.data.sev

import de.traewelling.app.data.model.CheckinInfo
import de.traewelling.app.data.model.SevMap
import de.traewelling.app.data.model.SevPoint
import de.traewelling.app.data.model.SevStopInfo
import de.traewelling.app.data.model.StopStation
import de.traewelling.app.data.model.TrainStation
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Match public SEV points without replacing API station or stopover identity. */
object SevStopResolver {
    private val berlin = ZoneId.of("Europe/Berlin")
    private const val MAX_MAP_AGE_MILLIS = 24 * 60 * 60 * 1000L
    private const val MAX_STATION_DISTANCE_METERS = 1_500.0
    private const val MAX_POINT_DISTANCE_METERS = 3_000.0
    private val regionalLine = Regex("^(?:bus\\s+)?(?:re|rb)\\s*\\d+[a-z]?(?:\\s+.*)?$", RegexOption.IGNORE_CASE)

    fun isReplacementBus(checkin: CheckinInfo): Boolean =
        listOf(checkin.category, checkin.mode).any { it?.trim()?.equals("bus", true) == true } &&
            checkin.lineName?.trim()?.let(regionalLine::matches) == true

    fun stationSlug(station: TrainStation): String? {
        val name = station.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val slug = normalise(name).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return slug.takeIf { it.isNotEmpty() && it.length <= 160 }
    }

    /** No route index: repeated visits are separated by their own planned times. */
    fun visitKey(stop: StopStation): String = stop.uuid?.takeIf { it.isNotBlank() }
        ?: "${stop.stationId ?: "unknown"}:${stop.arrivalPlanned}:${stop.departurePlanned}"

    fun resolve(
        checkin: CheckinInfo,
        fullRoute: List<StopStation>,
        maps: Map<String, SevMap>,
        nowMillis: Long
    ): Map<String, SevStopInfo> {
        if (!isReplacementBus(checkin) || nowMillis <= 0) return emptyMap()
        val today = localDate(nowMillis) ?: return emptyMap()
        val duplicatedKeys = fullRoute.groupingBy(::visitKey).eachCount().filterValues { it > 1 }.keys
        return buildMap {
            fullRoute.forEachIndexed { index, stop ->
                if (stop.cancelled == true) return@forEachIndexed
                val station = stop.station ?: return@forEachIndexed
                val slug = stationSlug(station) ?: return@forEachIndexed
                val map = maps[slug] ?: return@forEachIndexed
                val key = visitKey(stop)
                val guidance = map.notes.map(String::trim).filter(String::isNotEmpty).joinToString("\n\n")
                    .ifEmpty { "Die offizielle Bahnhofskarte zeigt die Ersatzhaltestellen." }
                fun unknown(reason: String): SevStopInfo = SevStopInfo(
                    map.sourceUrl, null, guidance, null, null, reason
                )
                val stationLat = station.latitude
                val stationLon = station.longitude
                val identityValid = map.slug == slug &&
                    map.sourceUrl in setOf("https://www.bahnhof.de/$slug/karte", "https://bahnhof.de/$slug/karte") &&
                    stationLat != null && stationLon != null && validCoordinates(stationLat, stationLon) &&
                    validCoordinates(map.stationLatitude, map.stationLongitude) &&
                    distance(stationLat, stationLon, map.stationLatitude, map.stationLongitude) <= MAX_STATION_DISTANCE_METERS
                if (!identityValid) {
                    put(key, unknown("Die SEV-Karte kann diesem Bahnhof nicht sicher zugeordnet werden."))
                    return@forEachIndexed
                }
                if (key in duplicatedKeys || (stop.uuid.isNullOrBlank() &&
                        (stop.stationId == null || listOf(stop.arrivalPlanned, stop.departurePlanned).none { value ->
                            value != null && runCatching { Instant.parse(value) }.isSuccess
                        }))) {
                    put(key, unknown("Der konkrete Halt ist nicht eindeutig zugeordnet."))
                    return@forEachIndexed
                }
                if (map.fetchedAtMillis <= 0 || map.fetchedAtMillis > nowMillis ||
                    nowMillis - map.fetchedAtMillis > MAX_MAP_AGE_MILLIS) {
                    put(key, unknown("Die SEV-Karte ist nicht aktuell; die Einstiegsposition bleibt unbestätigt."))
                    return@forEachIndexed
                }
                val eventDate = listOf(stop.departurePlanned, stop.arrivalPlanned).firstNotNullOfOrNull { value ->
                    value?.let { runCatching { Instant.parse(it).atZone(berlin).toLocalDate() }.getOrNull() }
                } ?: today
                val validity = mapValidity(map.notes, today, eventDate)
                if (validity != null) {
                    put(key, unknown(validity))
                    return@forEachIndexed
                }
                val points = map.points.distinct()
                if (points.isEmpty()) {
                    put(key, unknown("Die Bahnhofskarte enthält keine SEV-Position."))
                    return@forEachIndexed
                }
                if (points.any { it.id.isBlank() || !validCoordinates(it.latitude, it.longitude) ||
                        distance(it.latitude, it.longitude, map.stationLatitude, map.stationLongitude) > MAX_POINT_DISTANCE_METERS } ||
                    points.groupBy { it.id }.any { (_, values) -> values.size > 1 }) {
                    put(key, unknown("Die Ersatzhaltestellen enthalten widersprüchliche oder ungültige Positionsdaten."))
                    return@forEachIndexed
                }
                val chosen = if (points.size == 1 && points.single().label.isNullOrBlank()) points.single()
                    else chooseByDirection(points, fullRoute.drop(index + 1), today, eventDate)
                if (chosen == null) {
                    put(key, unknown("Mehrere oder richtungsabhängige Ersatzhaltestellen: Die passende Position ist nicht eindeutig bestätigt."))
                } else {
                    put(key, SevStopInfo(map.sourceUrl, chosen.label, guidance, chosen.latitude, chosen.longitude))
                }
            }
        }
    }

    private data class DateRange(val start: LocalDate, val end: LocalDate)
    private val dateRange = Regex(
        "(?:vom|von)\\s+(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?\\.?\\s*(?:bis|–|-)\\s*(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?!\\d)",
        RegexOption.IGNORE_CASE
    )

    private fun mapValidity(notes: List<String>, today: LocalDate, eventDate: LocalDate): String? {
        val ranges = mutableListOf<DateRange>()
        for (note in notes) {
            val matches = dateRange.findAll(note).toList()
            val unmatchedBound = Regex(
                "\\b(?:ab(?:\\s+dem)?|bis(?:\\s+zum)?|vom|von)\\s+\\d{1,2}\\.\\d{1,2}(?:\\.\\d{4})?",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(dateRange.replace(note, ""))
            // A broad known interval cannot prove that another ab/bis
            // restriction applies to the same point or may be ignored.
            if (unmatchedBound || (matches.isEmpty() &&
                    Regex("tempor[aä]r|vor[uü]bergehend", RegexOption.IGNORE_CASE).containsMatchIn(note))) {
                return "Die Gültigkeit der vorübergehenden Ersatzhaltestellen ist nicht eindeutig angegeben."
            }
            for (match in matches) {
                val range = runCatching {
                    val endYear = match.groupValues[6].toInt()
                    val startMonth = match.groupValues[2].toInt()
                    val endMonth = match.groupValues[5].toInt()
                    val startYear = match.groupValues[3].toIntOrNull() ?: (endYear - if (startMonth > endMonth) 1 else 0)
                    DateRange(LocalDate.of(startYear, startMonth, match.groupValues[1].toInt()),
                        LocalDate.of(endYear, endMonth, match.groupValues[4].toInt()))
                }.getOrNull() ?: return "Die Gültigkeitsdaten der Ersatzhaltestellen sind nicht auswertbar."
                if (range.end < range.start) return "Die Gültigkeitsdaten der Ersatzhaltestellen sind widersprüchlich."
                ranges += range
            }
        }
        if (ranges.distinct().size > 1) return "Mehrere Gültigkeitszeiträume erlauben keine eindeutige Haltestellenzuordnung."
        val range = ranges.firstOrNull() ?: return null
        return if (today < range.start || today > range.end || eventDate < range.start || eventDate > range.end)
            "Die angegebenen Ersatzhaltestellen gelten nicht für den aktuellen Fahrtzeitraum." else null
    }

    private data class Direction(val place: String, val until: LocalDate? = null)
    private val extraUntilDirection = Regex(
        "^Richtung\\s+(.+?)\\s+bis\\s+(?:zum\\s+)?(\\d{1,2}\\.\\d{1,2}\\.\\d{4})\\s*:\\s*Richtung\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val directionUntil = Regex(
        "^Richtung\\s+(.+?)\\s+bis\\s+(?:zum\\s+)?(\\d{1,2}\\.\\d{1,2}\\.\\d{4})$",
        RegexOption.IGNORE_CASE
    )

    private fun directions(label: String?): List<Direction> {
        val text = label?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        extraUntilDirection.matchEntire(text)?.let { match ->
            val until = parseGermanDate(match.groupValues[2]) ?: return emptyList()
            return places(match.groupValues[1]).map { Direction(it) } + places(match.groupValues[3]).map { Direction(it, until) }
        }
        directionUntil.matchEntire(text)?.let { match ->
            val until = parseGermanDate(match.groupValues[2]) ?: return emptyList()
            return places(match.groupValues[1]).map { Direction(it, until) }
        }
        if (Regex("\\d|\\b(?:bis|ab|vom|von)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return emptyList()
        val plain = Regex("^Richtung\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(text) ?: return emptyList()
        return places(plain.groupValues[1]).map { Direction(it) }
    }

    private fun places(value: String): List<String> = value.split(Regex("\\s*(?:/|;|\\bund\\b)\\s*", RegexOption.IGNORE_CASE))
        .map(::normalise).map { it.replace(Regex("[^a-z0-9]+"), " ").trim() }.filter { it.isNotEmpty() }

    private fun chooseByDirection(points: List<SevPoint>, following: List<StopStation>, today: LocalDate, eventDate: LocalDate): SevPoint? {
        val rules = points.associateWith { point -> directions(point.label) }
        // An unclassified point could serve the same direction as a labelled
        // one. Missing/unknown labels do not prove that it is an alternative.
        if (rules.values.any { it.isEmpty() }) return null
        for (next in following.filter { it.cancelled != true }) {
            val nextName = next.stationName?.let(::normalise)?.replace(Regex("[^a-z0-9]+"), " ")?.trim() ?: continue
            val matches = rules.filterValues { directions -> directions.any { rule ->
                nextName == rule.place || nextName.startsWith("${rule.place} ")
            } }
            if (matches.isNotEmpty()) {
                // The first known directional place remains decisive even if
                // its rule expired. A later opposing stop cannot revive it.
                return matches.filterValues { directions -> directions.any { rule ->
                    (nextName == rule.place || nextName.startsWith("${rule.place} ")) &&
                        (rule.until == null || (today <= rule.until && eventDate <= rule.until))
                } }.keys.singleOrNull()
            }
        }
        return null
    }

    private fun parseGermanDate(value: String): LocalDate? = runCatching {
        val parts = value.split('.').map(String::toInt)
        LocalDate.of(parts[2], parts[1], parts[0])
    }.getOrNull()

    private fun localDate(millis: Long): LocalDate? = runCatching { Instant.ofEpochMilli(millis).atZone(berlin).toLocalDate() }.getOrNull()

    private fun normalise(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"),
        Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "")

    private fun validCoordinates(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat = Math.toRadians(lat2 - lat1)
        val lon = Math.toRadians(lon2 - lon1)
        val a = sin(lat / 2) * sin(lat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(lon / 2) * sin(lon / 2)
        return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
