package com.trec.music.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class SettingsDataStore(private val context: Context) {
    private val store by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("trec_settings") }
        )
    }

    val legacyPrefsMigrated: Flow<Boolean> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs[Keys.LegacyPrefsMigrated] == true }

    suspend fun markLegacyPrefsMigrated() {
        store.edit { prefs ->
            prefs[Keys.LegacyPrefsMigrated] = true
        }
    }

    private object Keys {
        val LegacyPrefsMigrated: Preferences.Key<Boolean> =
            booleanPreferencesKey("legacy_prefs_migrated")
    }
}
