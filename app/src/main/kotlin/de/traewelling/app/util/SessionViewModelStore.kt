package de.traewelling.app.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore

/** Retains feature state across rotation, but never across credential generations. */
class SessionViewModelStore : ViewModel() {
    private var revision: String? = null
    private var store: ViewModelStore? = null

    fun forSession(sessionRevision: String): ViewModelStore {
        if (revision != sessionRevision) clearSession()
        return store ?: ViewModelStore().also { store = it; revision = sessionRevision }
    }

    fun clearSession() {
        store?.clear()
        store = null
        revision = null
    }

    override fun onCleared() { clearSession() }
}
