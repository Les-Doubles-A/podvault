package com.podvault.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "podvault_prefs")

/**
 * Section 64: DataStore-backed user preferences (watched folders, default playback
 * speed, skip intervals, theme). No account, no server — everything stays on-device.
 */
class PreferencesManager(private val context: Context) {

    private object Keys {
        val WATCHED_FOLDERS = stringSetPreferencesKey("watched_folders")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val REWIND_SECONDS = intPreferencesKey("rewind_seconds")
        val FORWARD_SECONDS = intPreferencesKey("forward_seconds")
        val DARK_THEME = booleanPreferencesKey("dark_theme_override")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val SMART_REWIND = booleanPreferencesKey("smart_rewind")
        val AMBIENT_MODE = booleanPreferencesKey("ambient_mode")
    }

    val watchedFolders: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.WATCHED_FOLDERS] ?: emptySet()
    }

    val defaultSpeed: Flow<Float> = context.dataStore.data.map {
        it[Keys.DEFAULT_SPEED] ?: 1.0f
    }

    val rewindSeconds: Flow<Int> = context.dataStore.data.map {
        it[Keys.REWIND_SECONDS] ?: 15
    }

    val forwardSeconds: Flow<Int> = context.dataStore.data.map {
        it[Keys.FORWARD_SECONDS] ?: 30
    }

    val hasOnboarded: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ONBOARDED] ?: false
    }

    /** Petit retour en arrière à la reprise après une pause (activé par défaut). */
    val smartRewind: Flow<Boolean> = context.dataStore.data.map { it[Keys.SMART_REWIND] ?: true }

    /** Mode ambiance du lecteur (couleurs de la pochette, réactif au son). */
    val ambientMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.AMBIENT_MODE] ?: false }

    val darkThemeOverride: Flow<Boolean?> = context.dataStore.data.map { it[Keys.DARK_THEME] }
    val dynamicColors: Flow<Boolean> = context.dataStore.data.map { it[Keys.DYNAMIC_COLORS] ?: false }

    suspend fun setDarkThemeOverride(enabled: Boolean?) {
        context.dataStore.edit { prefs ->
            if (enabled == null) prefs.remove(Keys.DARK_THEME) else prefs[Keys.DARK_THEME] = enabled
        }
    }

    suspend fun setAmbientMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMBIENT_MODE] = enabled }
    }

    suspend fun setSmartRewind(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SMART_REWIND] = enabled }
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLORS] = enabled }
    }

    suspend fun addWatchedFolder(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.WATCHED_FOLDERS] ?: emptySet()
            prefs[Keys.WATCHED_FOLDERS] = current + uri
            prefs[Keys.ONBOARDED] = true
        }
    }

    suspend fun removeWatchedFolder(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.WATCHED_FOLDERS] ?: emptySet()
            prefs[Keys.WATCHED_FOLDERS] = current - uri
        }
    }

    suspend fun setDefaultSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.DEFAULT_SPEED] = speed }
    }

    fun speedForPodcast(podcastId: Long): Flow<Float> = context.dataStore.data.map {
        it[floatPreferencesKey("speed_$podcastId")] ?: it[Keys.DEFAULT_SPEED] ?: 1.0f
    }

    suspend fun setSpeedForPodcast(podcastId: Long, speed: Float) {
        context.dataStore.edit { it[floatPreferencesKey("speed_$podcastId")] = speed.coerceIn(0.5f, 3.0f) }
    }

    suspend fun setSkipIntervals(rewind: Int, forward: Int) {
        context.dataStore.edit {
            it[Keys.REWIND_SECONDS] = rewind
            it[Keys.FORWARD_SECONDS] = forward
        }
    }
}
