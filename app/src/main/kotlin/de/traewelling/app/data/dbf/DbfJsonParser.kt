package de.traewelling.app.data.dbf

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.time.Instant
import java.time.LocalTime

/** The documented IRIS JSON v3 branch of derf/db-fakedisplay's Stationboard renderer. */
object DbfJsonParser {
    private const val MAX_JSON_CHARS = 2 * 1024 * 1024
    private const val MAX_DEPARTURES = 5_000
    private val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    private val integerPattern = Regex("-?(?:0|[1-9]\\d*)")

    /** Throw on a failed or unexpected response instead of treating it as an empty board. */
    fun parse(json: String, eva: String, fetchedAt: Instant = Instant.now()): DbfBoard {
        require(normalizedEva(eva) != null) { "Invalid DBF station EVA" }
        require(json.length <= MAX_JSON_CHARS) { "DBF response is too large" }
        val reader = JsonReader(StringReader(json)).apply { strictness = Strictness.STRICT }
        val root = reader.use {
            val parsed = JsonParser.parseReader(it)
            require(it.peek() == JsonToken.END_DOCUMENT) { "Trailing data in DBF response" }
            parsed
        }
        require(root.isJsonObject) { "Expected a DBF station board" }
        val objectRoot = root.asJsonObject
        require(!objectRoot.has("error")) { "DBF returned an error" }
        val rows = objectRoot.get("departures")
        require(rows != null && rows.isJsonArray) { "DBF departures are missing" }
        require(rows.asJsonArray.size() <= MAX_DEPARTURES) { "Too many DBF departures" }
        val departures = rows.asJsonArray.map { row ->
            require(row.isJsonObject) { "Invalid DBF departure" }
            val obj = row.asJsonObject
            // HAFAS and other backends also expose JSON, but use epoch fields instead.
            require(obj.has("scheduledArrival") && obj.has("scheduledDeparture") &&
                obj.has("delayArrival") && obj.has("delayDeparture")) { "Expected DBF IRIS JSON v3" }
            DbfDeparture(
                trainNumber = obj.string("trainNumber"),
                scheduledArrival = obj.time("scheduledArrival"),
                scheduledDeparture = obj.time("scheduledDeparture"),
                delayArrival = obj.integer("delayArrival"),
                delayDeparture = obj.integer("delayDeparture"),
                platform = obj.string("platform"),
                scheduledPlatform = obj.string("scheduledPlatform"),
                isCancelled = obj.booleanOrFlag("isCancelled"),
                missingRealtime = obj.booleanOrFlag("missingRealtime"),
                messages = obj.messages()
            )
        }
        return DbfBoard(eva.trim(), departures.toList(), fetchedAt)
    }

    internal fun normalizedEva(value: String?): String? = value?.trim()
        ?.takeIf { it.length == 7 && it.all(Char::isDigit) && it.toLongOrNull() != 0L }

    private fun JsonObject.string(key: String): String? = get(key)?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.time(key: String): LocalTime? = string(key)?.takeIf(timePattern::matches)
        ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    private fun JsonObject.integer(key: String): Int? {
        val value = get(key)?.takeUnless(JsonElement::isJsonNull) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asString.takeIf(integerPattern::matches)?.toIntOrNull()
    }

    private fun JsonObject.booleanOrFlag(key: String): Boolean? {
        val value = get(key)?.takeUnless(JsonElement::isJsonNull) ?: return null
        if (!value.isJsonPrimitive) return null
        val primitive = value.asJsonPrimitive
        if (primitive.isBoolean) return primitive.asBoolean
        if (primitive.isNumber) return when (primitive.asString) { "0" -> false; "1" -> true; else -> null }
        return null
    }

    private fun JsonObject.messages(): List<DbfMessage> {
        val groups = get("messages")?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return emptyList()
        return listOf("delay", "qos").flatMap { group ->
            groups.get(group)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.mapNotNull { raw ->
                val item = raw.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                val text = item.string("text") ?: return@mapNotNull null
                // This is a message creation time; it must not be attached to predictions.
                DbfMessage(text, item.string("timestamp"))
            } ?: emptyList()
        }
    }
}
