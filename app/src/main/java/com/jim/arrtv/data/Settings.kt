package com.jim.arrtv.data

import android.content.Context
import com.jim.arrtv.BuildConfig

data class ArrSettings(
    val radarrUrl: String,
    val radarrKey: String,
    val sonarrUrl: String,
    val sonarrKey: String,
) {
    val radarrConfigured get() = radarrUrl.isNotBlank() && radarrKey.isNotBlank()
    val sonarrConfigured get() = sonarrUrl.isNotBlank() && sonarrKey.isNotBlank()
}

/** Stores server details in SharedPreferences, falling back to dev defaults from local.properties. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("arr_settings", Context.MODE_PRIVATE)

    fun load() = ArrSettings(
        radarrUrl = prefs.getString(RADARR_URL, null) ?: BuildConfig.DEFAULT_RADARR_URL,
        radarrKey = prefs.getString(RADARR_KEY, null) ?: BuildConfig.DEFAULT_RADARR_KEY,
        sonarrUrl = prefs.getString(SONARR_URL, null) ?: BuildConfig.DEFAULT_SONARR_URL,
        sonarrKey = prefs.getString(SONARR_KEY, null) ?: BuildConfig.DEFAULT_SONARR_KEY,
    )

    fun save(settings: ArrSettings) {
        prefs.edit()
            .putString(RADARR_URL, settings.radarrUrl.trim())
            .putString(RADARR_KEY, settings.radarrKey.trim())
            .putString(SONARR_URL, settings.sonarrUrl.trim())
            .putString(SONARR_KEY, settings.sonarrKey.trim())
            .apply()
    }

    private companion object {
        const val RADARR_URL = "radarr_url"
        const val RADARR_KEY = "radarr_key"
        const val SONARR_URL = "sonarr_url"
        const val SONARR_KEY = "sonarr_key"
    }
}
