package de.traewelling.app.util

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app has one preferences file and must never construct competing DataStores for it. */
internal object PreferenceStorageRegistry {
    @Volatile private var instance: PreferenceStorage? = null

    fun get(context: Context): PreferenceStorage = instance ?: synchronized(this) {
        instance ?: run {
            val application = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reset = PreferenceCorruptionReset()
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = reset.handler,
                scope = scope,
                produceFile = { application.preferencesDataStoreFile("traewelling_prefs") }
            )
            PreferenceStorage(store, scope, reset).also { instance = it }
        }
    }
}
