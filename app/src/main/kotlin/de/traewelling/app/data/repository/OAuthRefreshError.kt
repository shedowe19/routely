package de.traewelling.app.data.repository

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import retrofit2.Response
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Only a well-formed, explicit OAuth grant error permits clearing refresh credentials. */
internal object OAuthRefreshError {
    private const val MAX_ERROR_BYTES = 16 * 1024L

    fun isInvalidGrant(response: Response<*>): Boolean {
        if (response.code() !in setOf(400, 401, 403)) return false
        val body = response.errorBody() ?: return false
        return try {
            body.use {
                val source = it.source()
                source.request(MAX_ERROR_BYTES + 1)
                if (source.buffer.size > MAX_ERROR_BYTES) return false
                val json = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source.readByteArray())).toString()
                JsonReader(StringReader(json)).use { reader ->
                    reader.strictness = Strictness.STRICT
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) return false
                    reader.beginObject()
                    var error: String? = null
                    var hasError = false
                    while (reader.hasNext()) {
                        if (reader.nextName() == "error") {
                            if (hasError || reader.peek() != JsonToken.STRING) return false
                            hasError = true
                            error = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                    reader.peek() == JsonToken.END_DOCUMENT && error == "invalid_grant"
                }
            }
        } catch (_: IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }
}
