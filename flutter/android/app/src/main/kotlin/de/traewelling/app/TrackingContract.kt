package de.traewelling.app

/** Pure authorization contract shared by UI commands, service and intents. */
internal object TrackingContract {
    fun permitsLocation(precise: Boolean, approximate: Boolean, recognition: Boolean = false): Boolean =
        precise || (approximate && !recognition)

    fun isFreshLocation(acquiredNanos: Long, nowNanos: Long): Boolean =
        acquiredNanos > 0 && nowNanos >= acquiredNanos && nowNanos - acquiredNanos <= 30_000_000_000L

    private data class Identity(val revision: String, val status: Int, val generation: Long)
    private fun identity(map: Map<String, Any?>): Identity? {
        val revision = map["sessionRevision"] as? String ?: return null
        val status = (map["statusId"] as? Number)?.toInt() ?: return null
        val generation = (map["generation"] as? Number)?.toLong() ?: return null
        if (revision.isBlank() || status < 0 || generation < 0) return null
        return Identity(revision, status, generation)
    }
    fun matches(expected: Map<String, Any?>?, command: Map<String, Any?>): Boolean {
        val current = expected?.let(::identity) ?: return false
        return current == identity(command)
    }
    fun acceptsStart(current: Map<String, Any?>?, highestGeneration: Long, next: Map<String, Any?>): Boolean {
        val identity = identity(next) ?: return false
        if (identity.status == 0 && next["mode"] != "recognition") return false
        if (identity.generation < highestGeneration || (identity.generation == highestGeneration && !matches(current, next))) return false
        return !containsSecret(next)
    }
    fun sameAlertOwner(current: Map<String, Any?>?, next: Map<String, Any?>): Boolean {
        val old = current?.let(::identity) ?: return false
        val new = identity(next) ?: return false
        if (old.revision != new.revision || old.status != new.status || old.status == 0) return false
        val oldJourney = (current["route"] as? Map<*, *>)?.get("journey") as? Map<*, *> ?: return false
        val newJourney = (next["route"] as? Map<*, *>)?.get("journey") as? Map<*, *> ?: return false
        return oldJourney["tripIdentity"] == newJourney["tripIdentity"] &&
            (oldJourney["contentRevision"] as? Number)?.toLong() == (newJourney["contentRevision"] as? Number)?.toLong()
    }
    fun containsSecret(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value.any { (key, child) -> key.toString().lowercase() in setOf(
            "token", "accesstoken", "access_token", "refreshtoken", "refresh_token", "clientsecret", "client_secret", "password") || containsSecret(child) }
        is Iterable<*> -> value.any(::containsSecret)
        else -> false
    }
}
