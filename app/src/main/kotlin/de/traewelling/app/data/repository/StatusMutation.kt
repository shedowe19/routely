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

    /** Like endpoints return no full status; update only the current user's like intent. */
    data class LikeChanged(override val sessionRevision: String, override val statusId: Int, val liked: Boolean) :
        StatusMutation(sessionRevision, statusId)

    /** The PUT committed, but its response does not contain a trustworthy replacement snapshot. */
    data class Invalidated(override val sessionRevision: String, override val statusId: Int) :
        StatusMutation(sessionRevision, statusId)
}

/** A bounded, account-partitioned commit watermark, including when no replacement body exists. */
internal data class StatusMutationSnapshot(val revision: Long, val mutation: StatusMutation?)

internal data class StatusDetailSnapshot(val status: Status, val revision: Long)

/** Shared by repository instances. Tests can supply a private store instead. */
internal class StatusMutationStore {
    internal val cacheMutex = Mutex()
    // A fixed stripe set bounds memory while sharing write ordering across repository instances.
    // Hash collisions only serialize unrelated writes; the same credentials/status always share a lock.
    private val writeMutexes = Array(64) { Mutex() }

    internal fun writeMutex(accountKey: String, statusId: Int): Mutex =
        writeMutexes[((31 * accountKey.hashCode() + statusId) and Int.MAX_VALUE) % writeMutexes.size]
    internal var cacheRevision = 0L // Read and written only while holding cacheMutex.
    private val accountRevisions = mutableMapOf<String, Long>()
    private val contentMutations = linkedMapOf<Pair<String, Int>, StatusMutationSnapshot>()
    private var contentRevision = 0L
    private val accountContentRevisions = mutableMapOf<String, Long>()
    private var contentEpochOverflow = false
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

    /** Likes cannot erase an unconsumed destination edit. Access is protected by cacheMutex. */
    internal fun recordContentMutation(accountKey: String, event: StatusMutation) {
        if (event is StatusMutation.LikeChanged) return
        ++contentRevision
        if (!contentEpochOverflow) {
            accountContentRevisions[accountKey] = contentRevision
            if (accountContentRevisions.size > 64) { accountContentRevisions.clear(); contentEpochOverflow = true }
        }
        val key = accountKey to event.statusId
        contentMutations.remove(key)
        contentMutations[key] = StatusMutationSnapshot(contentRevision, event)
        while (contentMutations.size > 64) contentMutations.remove(contentMutations.keys.first())
    }

    internal fun contentSnapshot(accountKey: String, statusId: Int): StatusMutationSnapshot =
        contentMutations[accountKey to statusId]
            // Eviction conservatively invalidates a previously held read/model, rather than
            // treating the missing ledger entry as proof that no mutation happened.
            ?: StatusMutationSnapshot(if (contentEpochOverflow) contentRevision else accountContentRevisions[accountKey] ?: 0L, null)

    /** A verified GET installs a per-status baseline, without erasing an unconsumed full PUT. */
    internal fun rememberContentBaseline(accountKey: String, statusId: Int, revision: Long) {
        val key = accountKey to statusId
        if (key !in contentMutations) contentMutations[key] = StatusMutationSnapshot(revision, null)
        while (contentMutations.size > 64) contentMutations.remove(contentMutations.keys.first())
    }

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
