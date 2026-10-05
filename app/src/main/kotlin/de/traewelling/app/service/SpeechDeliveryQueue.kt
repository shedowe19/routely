package de.traewelling.app.service

/** Links one asynchronous speech attempt to its visit and owning service generation. */
internal enum class SpeechDeliveryKind { STOP, TRIP_CHANGE }

internal data class SpeechDelivery(
    val utteranceId: String,
    val statusId: Int,
    val generation: Long,
    val stopKey: String,
    val kind: SpeechDeliveryKind = SpeechDeliveryKind.STOP
)

/** Main-confined bookkeeping, independent of Android's TTS implementation. */
internal class SpeechDeliveryQueue {
    private var sequence = 0L
    private val active = linkedMapOf<String, SpeechDelivery>()

    val isEmpty: Boolean get() = active.isEmpty()

    fun find(statusId: Int, generation: Long, stopKey: String, kind: SpeechDeliveryKind = SpeechDeliveryKind.STOP): SpeechDelivery? =
        active.values.firstOrNull {
            it.statusId == statusId && it.generation == generation && it.stopKey == stopKey && it.kind == kind
        }

    fun begin(statusId: Int, generation: Long, stopKey: String, kind: SpeechDeliveryKind = SpeechDeliveryKind.STOP): SpeechDelivery =
        find(statusId, generation, stopKey, kind) ?: SpeechDelivery(
            utteranceId = "${kind.name.lowercase()}:$statusId:$generation:${++sequence}",
            statusId = statusId,
            generation = generation,
            stopKey = stopKey,
            kind = kind
        ).also { active[it.utteranceId] = it }

    fun contains(utteranceId: String): Boolean = utteranceId in active

    /** A late or duplicate terminal callback cannot remove a newer attempt. */
    fun finish(utteranceId: String): SpeechDelivery? = active.remove(utteranceId)

    fun clear() = active.clear()
}
