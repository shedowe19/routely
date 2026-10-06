package de.traewelling.app.service

/** One bounded retry series. Older platform callbacks cannot reopen a stopped owner. */
internal class LifecycleRetryBudget(
    private val delaysMillis: List<Long> = listOf(2_000L, 5_000L, 15_000L)
) {
    private var newestOwner = Long.MIN_VALUE
    private var activeOwner: Long? = null
    private var failures = 0

    init { require(delaysMillis.all { it > 0L }) }

    fun begin(owner: Long): Boolean {
        if (owner < newestOwner || (owner == newestOwner && activeOwner == null)) return false
        if (owner != activeOwner) {
            newestOwner = owner
            activeOwner = owner
            failures = 0
        }
        return true
    }

    fun failure(owner: Long): Long? = if (activeOwner == owner) delaysMillis.getOrNull(failures++) else null

    fun success(owner: Long): Boolean {
        if (activeOwner != owner) return false
        failures = 0
        return true
    }

    fun stop() { activeOwner = null }
}
