package de.traewelling.app.data.sev

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.traewelling.app.data.model.SevMap
import de.traewelling.app.data.model.SevPoint
import java.util.ArrayDeque

/** Decodes public map data as JSON; page JavaScript is never executed. */
object BahnhofSevParser {
    private const val TYPE = "RAIL_REPLACEMENT_TRANSPORT"
    private val slugPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val scripts = Regex("<script\\b[^>]*>(.*?)</script\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val push = Regex("self\\.__next_f\\.push\\(\\s*")
    private val record = Regex("^[0-9a-fA-F]+:(.*)$")

    fun parse(html: String, expectedSlug: String, fetchedAtMillis: Long): SevMap? {
        if (!isValidSlug(expectedSlug) || fetchedAtMillis < 0 || html.length > MAX_HTML_BYTES) return null
        return try {
            parseValidated(html, expectedSlug, fetchedAtMillis)
        } catch (_: RuntimeException) {
            // A changed or malformed page is an unavailable optional data source.
            null
        }
    }

    internal fun isValidSlug(slug: String): Boolean = slug.length <= 120 && slugPattern.matches(slug)

    private fun parseValidated(html: String, slug: String, fetched: Long): SevMap? {
        val chunks = StringBuilder()
        scripts.findAll(html).forEach { script ->
            val contents = script.groupValues[1]
            push.findAll(contents).forEach pushLoop@ { match ->
                val end = arrayEnd(contents, match.range.last + 1) ?: return@pushLoop
                if (!contents.substring(end).trimStart().startsWith(")")) return@pushLoop
                val value = json(contents.substring(match.range.last + 1, end)) ?: return@pushLoop
                if (!value.isJsonArray) return@pushLoop
                val array = value.asJsonArray
                if (array.size() >= 2 && number(array[0]) == 1.0 && text(array[1]) != null) {
                    chunks.append(array[1].asString)
                }
            }
        }
        var candidate: JsonObject? = null
        var visited = 0
        chunks.lineSequence().forEach { line ->
            val payload = record.matchEntire(line)?.groupValues?.get(1) ?: return@forEach
            val root = json(payload) ?: return@forEach
            val pending = ArrayDeque<JsonElement>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                if (++visited > 100_000) return null
                val node = pending.removeLast()
                when {
                    node.isJsonObject -> {
                        val obj = node.asJsonObject
                        if (text(obj.get("slug")) == slug && obj.get("poi")?.isJsonObject == true) {
                            if (candidate != null && candidate != obj) return null
                            candidate = obj
                        }
                        obj.entrySet().forEach { pending.add(it.value) }
                    }
                    node.isJsonArray -> node.asJsonArray.forEach { pending.add(it) }
                }
            }
        }
        val data = candidate ?: return null
        val location = data.getAsJsonObject("location") ?: return null
        val latitude = number(location.get("latitude")) ?: return null
        val longitude = number(location.get("longitude")) ?: return null
        if (!validCoordinate(latitude, longitude)) return null
        val poi = data.getAsJsonObject("poi")
        val features = poi.get(TYPE)?.let { if (it.isJsonArray) it.asJsonArray else return null }
            ?: JsonArray()
        val points = linkedMapOf<String, SevPoint>()
        for (feature in features) {
            if (!feature.isJsonObject) return null
            val obj = feature.asJsonObject
            val properties = obj.getAsJsonObject("properties") ?: return null
            val geometry = obj.getAsJsonObject("geometry") ?: return null
            if (text(obj.get("type")) != "Feature" || text(properties.get("type")) != TYPE ||
                text(geometry.get("type")) != "Point") return null
            val coordinates = geometry.getAsJsonArray("coordinates") ?: return null
            if (coordinates.size() != 2) return null
            val lon = number(coordinates[0]) ?: return null
            val lat = number(coordinates[1]) ?: return null
            if (!validCoordinate(lat, lon)) return null
            val id = cleaned(obj.get("id")) ?: return null
            val propertyId = cleaned(properties.get("id"))
            if (propertyId != null && propertyId != id) return null
            val point = SevPoint(id, lat, lon, lines(properties.get("name")), cleaned(properties.get("version")))
            if (points.containsKey(id) && points[id] != point) return null
            points[id] = point
        }
        val notes = data.get("notes")?.let { if (it.isJsonObject) it.asJsonObject else return null }
        val notices = notes?.get(TYPE)?.let { if (it.isJsonArray) it.asJsonArray else return null }
        val bodies = notices?.mapNotNull { note ->
            if (!note.isJsonObject) return null
            lines(note.asJsonObject.get("body"))
        }?.distinct().orEmpty()
        return SevMap(slug, "https://www.bahnhof.de/$slug/karte", latitude, longitude,
            points.values.toList(), bodies, fetched)
    }

    private fun json(value: String): JsonElement? = try {
        JsonParser.parseString(value)
    } catch (_: RuntimeException) { null }

    /** Locate the JSON argument without accepting executable expressions or evaluating escapes. */
    private fun arrayEnd(script: String, start: Int): Int? {
        if (start >= script.length || script[start] != '[') return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until script.length) {
            val char = script[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '[', '{' -> depth++
                ']', '}' -> if (--depth == 0) return index + 1
            }
        }
        return null
    }

    private fun text(value: JsonElement?): String? = value?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString

    private fun number(value: JsonElement?): Double? = value?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isNumber
    }?.asDouble?.takeIf { it.isFinite() }

    private fun cleaned(value: JsonElement?): String? = text(value)?.trim()
        ?.takeUnless { it == "$" + "undefined" }?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    private fun lines(value: JsonElement?): String? = text(value)?.trim()?.takeUnless { it == "$" + "undefined" }
        ?.lineSequence()?.joinToString("\n") { it.trim().replace(Regex("[\\t ]+"), " ") }
        ?.trim()?.takeIf { it.isNotEmpty() }

    private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    internal const val MAX_HTML_BYTES = 8 * 1024 * 1024
}
