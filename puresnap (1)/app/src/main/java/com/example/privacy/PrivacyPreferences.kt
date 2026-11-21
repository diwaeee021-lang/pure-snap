package com.example.privacy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.privacyDataStore: DataStore<Preferences> by preferencesDataStore(name = "puresnap_privacy_prefs")

data class PrivacySettings(
    val trackerBlockerEnabled: Boolean = true,
    val urlSanitizerEnabled: Boolean = true,
    val privateBrowserMode: Boolean = true,
    val wifiOnlyDownloads: Boolean = false,
    val darkTheme: Boolean = true,
    val totalTrackersBlocked: Long = 0L,
    val totalParamsStripped: Long = 0L
)

class PrivacyPreferences(private val context: Context) {

    private object Keys {
        val TRACKER_BLOCKER = booleanPreferencesKey("tracker_blocker_enabled")
        val URL_SANITIZER = booleanPreferencesKey("url_sanitizer_enabled")
        val PRIVATE_BROWSER = booleanPreferencesKey("private_browser_mode")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val TRACKERS_BLOCKED = longPreferencesKey("total_trackers_blocked")
        val PARAMS_STRIPPED = longPreferencesKey("total_params_stripped")
    }

    val settingsFlow: Flow<PrivacySettings> = context.privacyDataStore.data.map { prefs ->
        PrivacySettings(
            trackerBlockerEnabled = prefs[Keys.TRACKER_BLOCKER] ?: true,
            urlSanitizerEnabled = prefs[Keys.URL_SANITIZER] ?: true,
            privateBrowserMode = prefs[Keys.PRIVATE_BROWSER] ?: true,
            wifiOnlyDownloads = prefs[Keys.WIFI_ONLY] ?: false,
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            totalTrackersBlocked = prefs[Keys.TRACKERS_BLOCKED] ?: 0L,
            totalParamsStripped = prefs[Keys.PARAMS_STRIPPED] ?: 0L
        )
    }

    suspend fun setTrackerBlocker(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.TRACKER_BLOCKER] = enabled }
    }

    suspend fun setUrlSanitizer(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.URL_SANITIZER] = enabled }
    }

    suspend fun setPrivateBrowserMode(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.PRIVATE_BROWSER] = enabled }
    }

    suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.privacyDataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    suspend fun incrementTrackersBlocked(count: Long = 1L) {
        if (count <= 0L) return
        context.privacyDataStore.edit { prefs ->
            val current = prefs[Keys.TRACKERS_BLOCKED] ?: 0L
            prefs[Keys.TRACKERS_BLOCKED] = current + count
        }
    }

    suspend fun incrementParamsStripped(count: Long) {
        if (count <= 0L) return
        context.privacyDataStore.edit { prefs ->
            val current = prefs[Keys.PARAMS_STRIPPED] ?: 0L
            prefs[Keys.PARAMS_STRIPPED] = current + count
        }
    }

    suspend fun resetCounters() {
        context.privacyDataStore.edit { prefs ->
            prefs[Keys.TRACKERS_BLOCKED] = 0L
            prefs[Keys.PARAMS_STRIPPED] = 0L
        }
    }
}
