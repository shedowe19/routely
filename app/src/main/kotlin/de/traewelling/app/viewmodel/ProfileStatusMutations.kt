package de.traewelling.app.viewmodel

import de.traewelling.app.data.model.Status
import de.traewelling.app.data.repository.StatusMutation
import de.traewelling.app.util.AuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Profile cards share the same successful status writes, without inserting unknown cards. */
internal fun List<Status>.applyProfileMutation(mutation: StatusMutation): List<Status> = when (mutation) {
    is StatusMutation.Deleted, is StatusMutation.Invalidated -> filterNot { it.id == mutation.statusId }
    is StatusMutation.Updated -> map { if (it.id == mutation.statusId) mutation.status else it }
    is StatusMutation.LikeChanged -> map { status ->
        if (status.id != mutation.statusId) status else {
            val count = status.likes ?: 0
            val likes = if (status.liked == mutation.liked) count
                else if (mutation.liked) count + 1 else (count - 1).coerceAtLeast(0)
            status.copy(liked = mutation.liked, likes = likes)
        }
    }
}

internal fun CoroutineScope.observeProfileMutations(
    sessionProvider: suspend () -> AuthSession,
    mutations: Flow<StatusMutation>,
    onMutation: (StatusMutation) -> Unit
) = launch {
    val boundSession = sessionProvider()
    coroutineContext.ensureActive()
    mutations.collect { mutation ->
        if (mutation.sessionRevision != boundSession.revision) return@collect
        val current = sessionProvider()
        coroutineContext.ensureActive()
        if (current != boundSession || current.accessToken.isNullOrBlank()) return@collect
        onMutation(mutation)
    }
}
