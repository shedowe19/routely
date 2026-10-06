package de.traewelling.app.service

/** Only validated starts supersede earlier requests; stale credentials never supersede a valid pending start. */
internal class SessionStartRequests {
    private var sequence = 0L
    private var latestAccepted = 0L
    private data class Request(val revision: String?, val statusId: Int?)
    private val pending = linkedMapOf<Long, Request>()

    fun begin(revision: String?, statusId: Int? = null): Long = (++sequence).also { pending[it] = Request(revision, statusId) }

    fun accept(request: Long, revision: String): Boolean {
        if (!pending.containsKey(request) || request < latestAccepted) return false
        val expected = pending.getValue(request).revision
        if (expected != null && expected != revision) return false
        latestAccepted = request
        return true
    }

    fun hasPending(revision: String, statusId: Int? = null): Boolean = pending.values.any {
        (it.revision == null || it.revision == revision) &&
            (statusId == null || it.statusId == null || it.statusId == statusId)
    }
    fun finish(request: Long) { pending.remove(request) }
    fun clear() { pending.clear() }
}
