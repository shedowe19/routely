package de.traewelling.app.data.repository

import de.traewelling.app.data.model.Status
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex

/** Only the current login revision may consume a mutation; no credentials enter the event. */
sealed class StatusMutation(open val sessionRevision: String, open val statusId: Int) {
    data class Updated(override val sessionRevision: String, val status: Status) :
        StatusMutation(sessionRevision, status.id)

    data class Deleted(override val sessionRevision: String, override val statusId: Int) :
        StatusMutation(sessionRevision, statusId)

    /** The PUT committed, but its response does not contain a trustworthy replacement snapshot. */
    data class Invalidated(override val sessionRevision: String, override val statusId: Int) :
        StatusMutation(sessionRevision, statusId)
}

/** Shared by repository instances. Tests can supply a private store instead. */
internal class StatusMutationStore {
    internal val cacheMutex = Mutex()
    internal var cacheRevision = 0L // Read and written only while holding cacheMutex.
    private val accountRevisions = mutableMapOf<String, Long>()
    private var epochOverflow = false
    private val invalidCacheTypes = mutableSetOf<String>()
    private var offlineCacheDisabled = false
    private val mutableEvents = MutableSharedFlow<StatusMutation>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<StatusMutation> = mutableEvents.asSharedFlow()

    internal fun publish(event: StatusMutation) { mutableEvents.tryEmit(event) }

    internal fun revisionFor(accountKey: String): Long =
        if (epochOverflow) cacheRevision else accountRevisions[accountKey] ?: 0L

    internal fun invalidate(accountKey: String, types: List<String>) {
        ++cacheRevision
        if (!epochOverflow) {
            accountRevisions[accountKey] = cacheRevision
            if (accountRevisions.size > 64) {
                accountRevisions.clear()
                epochOverflow = true
                offlineCacheDisabled = true
            }
        }
        invalidCacheTypes.addAll(types)
        // Storage failure must not resurrect a committed mutation or create an unbounded RAM set.
        if (invalidCacheTypes.size > 64) {
            invalidCacheTypes.clear()
            offlineCacheDisabled = true
        }
    }

    internal fun canReadCache(type: String): Boolean = !offlineCacheDisabled && type !in invalidCacheTypes
    internal fun freshCacheWritten(type: String) { invalidCacheTypes.remove(type) }
}

object StatusMutationEvents {
    internal val store = StatusMutationStore()
    val events: SharedFlow<StatusMutation> = store.events
}
