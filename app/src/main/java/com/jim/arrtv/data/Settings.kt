package com.jim.arrtv.data

import android.content.Context
import com.jim.arrtv.BuildConfig

data class ArrSettings(
    val radarrUrl: String,
    val radarrKey: String,
    val sonarrUrl: String,
    val sonarrKey: String,
    /** For the popular titles on the Search tab. Optional. */
    val tmdbKey: String = "",
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
        tmdbKey = prefs.getString(TMDB_KEY, null) ?: BuildConfig.DEFAULT_TMDB_KEY,
    )

    fun save(settings: ArrSettings) {
        prefs.edit()
            .putString(RADARR_URL, settings.radarrUrl.trim())
            .putString(RADARR_KEY, settings.radarrKey.trim())
            .putString(SONARR_URL, settings.sonarrUrl.trim())
            .putString(SONARR_KEY, settings.sonarrKey.trim())
            .putString(TMDB_KEY, settings.tmdbKey.trim())
            .apply()
    }

    fun lastQualityProfile(kind: MediaKind): Int? =
        prefs.getInt("profile_${kind.name}", -1).takeIf { it >= 0 }

    fun setLastQualityProfile(kind: MediaKind, id: Int) {
        prefs.edit().putInt("profile_${kind.name}", id).apply()
    }

    /** Which kind the Search tab was last showing. */
    fun searchKind(): MediaKind =
        MediaKind.entries.firstOrNull { it.name == prefs.getString(SEARCH_KIND, null) } ?: MediaKind.MOVIE

    fun setSearchKind(kind: MediaKind) {
        prefs.edit().putString(SEARCH_KIND, kind.name).apply()
    }

    /**
     * The year filter for popular titles: that year or later. Null is any year. It's the year itself, so
     * "2024+" stays 2024+ when the new year comes.
     */
    fun popularFromYear(): Int? = prefs.getInt(POPULAR_FROM_YEAR, -1).takeIf { it > 0 }

    fun setPopularFromYear(year: Int?) {
        prefs.edit().putInt(POPULAR_FROM_YEAR, year ?: -1).apply()
    }

    /** For shows, the year they must have first aired in or after. Null is any year. */
    fun popularStartedYear(): Int? = prefs.getInt(POPULAR_STARTED_YEAR, -1).takeIf { it > 0 }

    fun setPopularStartedYear(year: Int?) {
        prefs.edit().putInt(POPULAR_STARTED_YEAR, year ?: -1).apply()
    }

    /** The minimum TMDB rating for popular titles, out of 10. Null is any rating. */
    fun popularMinRating(): Int? = prefs.getInt(POPULAR_MIN_RATING, -1).takeIf { it >= 0 }

    fun setPopularMinRating(rating: Int?) {
        prefs.edit().putInt(POPULAR_MIN_RATING, rating ?: -1).apply()
    }

    /** Whether the Search tab only shows movies already out on streaming. On unless turned off. */
    fun streamingOnly(): Boolean = prefs.getBoolean(STREAMING_ONLY, true)

    fun setStreamingOnly(streamingOnly: Boolean) {
        prefs.edit().putBoolean(STREAMING_ONLY, streamingOnly).apply()
    }

    /** How popular titles are arranged. Most popular unless changed. */
    fun popularSort(): PopularSort =
        PopularSort.entries.firstOrNull { it.name == prefs.getString(POPULAR_SORT, null) } ?: PopularSort.POPULAR

    fun setPopularSort(sort: PopularSort) {
        prefs.edit().putString(POPULAR_SORT, sort.name).apply()
    }

    /** The TMDB genre id for popular titles of [kind] (movies and shows have different ones). Null is any genre. */
    fun popularGenre(kind: MediaKind): Int? =
        prefs.getInt("${POPULAR_GENRE}_${kind.name}", -1).takeIf { id -> tmdbGenres(kind).any { it.first == id } }

    fun setPopularGenre(kind: MediaKind, genre: Int?) {
        prefs.edit().putInt("${POPULAR_GENRE}_${kind.name}", genre ?: -1).apply()
    }

    /** The streaming service popular titles are limited to. Null is any. */
    fun popularPlatform(): StreamingPlatform? =
        StreamingPlatform.entries.firstOrNull { it.name == prefs.getString(POPULAR_PLATFORM, null) }

    fun setPopularPlatform(platform: StreamingPlatform?) {
        prefs.edit().putString(POPULAR_PLATFORM, platform?.name).apply()
    }

    private companion object {
        const val RADARR_URL = "radarr_url"
        const val RADARR_KEY = "radarr_key"
        const val SONARR_URL = "sonarr_url"
        const val SONARR_KEY = "sonarr_key"
        const val TMDB_KEY = "tmdb_key"
        const val SEARCH_KIND = "search_kind"
        const val POPULAR_FROM_YEAR = "popular_from_year"
        const val POPULAR_STARTED_YEAR = "popular_started_year"
        const val POPULAR_MIN_RATING = "popular_min_rating"
        const val STREAMING_ONLY = "streaming_only"
        const val POPULAR_SORT = "popular_sort"
        const val POPULAR_GENRE = "popular_genre"
        const val POPULAR_PLATFORM = "popular_platform"
    }
}
