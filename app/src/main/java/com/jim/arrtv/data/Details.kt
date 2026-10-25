package com.jim.arrtv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Serializable
data class MovieDetails(
    val id: Int,
    val title: String,
    val year: Int = 0,
    val overview: String? = null,
    val runtime: Int = 0,
    val certification: String? = null,
    val genres: List<String> = emptyList(),
    val monitored: Boolean = false,
    val hasFile: Boolean = false,
    val isAvailable: Boolean = true,
    val qualityProfileId: Int = 0,
    val movieFile: MediaFile? = null,
    val tmdbId: Int = 0,
    val youTubeTrailerId: String? = null,
    val inCinemas: String? = null,
    val digitalRelease: String? = null,
    val physicalRelease: String? = null,
) {
    val release
        get() = Release.of(
            cinemas = parseUtc(inCinemas),
            streaming = listOfNotNull(parseUtc(digitalRelease), parseUtc(physicalRelease)).minOrNull(),
        )
}

@Serializable
data class MediaFile(val size: Double = 0.0, val quality: QueueQuality? = null)

@Serializable
data class SeriesDetails(
    val id: Int,
    val title: String,
    val year: Int = 0,
    val overview: String? = null,
    val network: String? = null,
    val status: String? = null,
    val certification: String? = null,
    val monitored: Boolean = false,
    val qualityProfileId: Int = 0,
    val seasons: List<SeasonInfo> = emptyList(),
    val statistics: SeriesStatistics? = null,
    val tmdbId: Int = 0,
)

@Serializable
data class SeasonInfo(val seasonNumber: Int, val monitored: Boolean = false, val statistics: SeasonStatistics? = null)

@Serializable
data class SeasonStatistics(
    val episodeFileCount: Int = 0,
    val episodeCount: Int = 0,
    val totalEpisodeCount: Int = 0,
    val sizeOnDisk: Double = 0.0,
)

@Serializable
data class SonarrEpisode(
    val id: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val airDateUtc: String? = null,
    val overview: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val episodeFile: MediaFile? = null,
)

val SonarrEpisode.code get() = "S%02dE%02d".format(Locale.ROOT, seasonNumber, episodeNumber)

fun seasonName(seasonNumber: Int) = if (seasonNumber == 0) "Specials" else "Season $seasonNumber"

@Serializable
data class EpisodeMonitor(val episodeIds: List<Int>, val monitored: Boolean)

/**
 * Everything the detail page shows for one library item. [raw] is the record exactly as Radarr/Sonarr
 * sent it, because edits have to PUT the whole thing back.
 */
sealed interface LibraryDetails {
    val raw: JsonObject
    val trailer: TrailerQuery

    data class Movie(override val raw: JsonObject, val movie: MovieDetails) : LibraryDetails {
        override val trailer
            get() = TrailerQuery(MediaKind.MOVIE, movie.title, movie.year, movie.tmdbId, movie.youTubeTrailerId)
    }

    data class Show(override val raw: JsonObject, val series: SeriesDetails, val episodes: List<SonarrEpisode>) :
        LibraryDetails {
        override val trailer get() = TrailerQuery(MediaKind.SHOW, series.title, series.year, series.tmdbId)
    }
}

fun formatSize(bytes: Double): String {
    val gb = 1024.0 * 1024 * 1024
    return if (bytes >= gb) "%.1f GB".format(Locale.ROOT, bytes / gb) else "%.0f MB".format(Locale.ROOT, bytes / (1024 * 1024))
}

fun formatRuntime(minutes: Int): String? = when {
    minutes <= 0 -> null
    minutes < 60 -> "${minutes}m"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** Parses the UTC timestamps Radarr/Sonarr send, like "2026-03-02T01:00:00Z". */
fun parseUtc(timestamp: String?): Date? {
    if (timestamp == null) return null
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return runCatching { format.parse(timestamp.take(19)) }.getOrNull()
}

/** A date in the TV's own locale, like "2 Mar 2026". */
fun formatDate(date: Date): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
