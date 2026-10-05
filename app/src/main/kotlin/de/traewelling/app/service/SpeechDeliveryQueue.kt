package de.traewelling.app.service

/** Links one asynchronous speech attempt to its visit and owning service generation. */
internal data class SpeechDelivery(
    val utteranceId: String,
    val statusId: Int,
    val generation: Long,
    val stopKey: String
)

/** Main-confined bookkeeping, independent of Android's TTS implementation. */
internal class SpeechDeliveryQueue {
    private var sequence = 0L
    private val active = linkedMapOf<String, SpeechDelivery>()

    val isEmpty: Boolean get() = active.isEmpty()

    fun find(statusId: Int, generation: Long, stopKey: String): SpeechDelivery? =
        active.values.firstOrNull {
            it.statusId == statusId && it.generation == generation && it.stopKey == stopKey
        }

    fun begin(statusId: Int, generation: Long, stopKey: String): SpeechDelivery =
        find(statusId, generation, stopKey) ?: SpeechDelivery(
            utteranceId = "stop:$statusId:$generation:${++sequence}",
            statusId = statusId,
            generation = generation,
            stopKey = stopKey
        ).also { active[it.utteranceId] = it }

    fun contains(utteranceId: String): Boolean = utteranceId in active

    /** A late or duplicate terminal callback cannot remove a newer attempt. */
    fun finish(utteranceId: String): SpeechDelivery? = active.remove(utteranceId)

    fun clear() = active.clear()
}
