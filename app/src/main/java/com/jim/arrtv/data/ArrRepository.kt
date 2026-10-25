package com.jim.arrtv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class MediaKind(val label: String, val service: String) {
    MOVIE("Movies", "Radarr"),
    SHOW("TV Shows", "Sonarr"),
}

/** Anything that can be drawn as a poster card. */
interface Poster {
    val key: String
    val title: String
    val subtitle: String
    val posterUrl: String?
}

/** A movie or show already in Radarr/Sonarr. [id] is its id there. [tmdbId] is 0 if Sonarr doesn't know it. */
data class MediaItem(
    val kind: MediaKind,
    val id: Int,
    override val title: String,
    override val subtitle: String,
    override val posterUrl: String?,
    val tmdbId: Int = 0,
) : Poster {
    override val key get() = "${kind.name}-$id"
}

data class SearchResult(
    val kind: MediaKind,
    override val key: String,
    override val title: String,
    override val subtitle: String,
    override val posterUrl: String?,
    val overview: String,
    val inLibrary: Boolean,
    /** Season numbers for a show, in order. 0 is specials. Empty for movies. */
    val seasons: List<Int>,
    val raw: JsonObject,
) : Poster

class ArrRepository(private val settings: ArrSettings) {
    private val radarr by lazy { ArrClient.create(settings.radarrUrl, settings.radarrKey, RadarrApi::class.java) }
    private val sonarr by lazy { ArrClient.create(settings.sonarrUrl, settings.sonarrKey, SonarrApi::class.java) }

    fun isConfigured(kind: MediaKind) = when (kind) {
        MediaKind.MOVIE -> settings.radarrConfigured
        MediaKind.SHOW -> settings.sonarrConfigured
    }

    suspend fun library(kind: MediaKind): List<MediaItem> = when (kind) {
        // Most recently downloaded first, like Plex's "Date Added". Ones with no file yet go by when they
        // were added to Radarr. The ISO timestamps sort correctly as text.
        MediaKind.MOVIE -> radarr.movies()
            .sortedWith(
                compareByDescending<RadarrMovie> { it.movieFile?.dateAdded ?: it.added.orEmpty() }
                    .thenBy { (it.sortTitle ?: it.title).lowercase() },
            )
            .map { m ->
                val status = when {
                    m.hasFile -> "Downloaded"
                    m.monitored -> "Wanted"
                    else -> "Not monitored"
                }
                MediaItem(MediaKind.MOVIE, m.id, m.title, "${m.year} · $status", m.images.posterUrl(settings.radarrUrl), m.tmdbId)
            }
        MediaKind.SHOW -> sonarr.series().let { series ->
            val lastAdded = lastEpisodeAdded(series)
            series.sortedWith(
                compareByDescending<SonarrSeries> { lastAdded[it.id] ?: it.added.orEmpty() }
                    .thenBy { (it.sortTitle ?: it.title).lowercase() },
            )
        }
            .map { s ->
                val episodes = s.statistics?.let { "${it.episodeFileCount}/${it.episodeCount} episodes" } ?: ""
                MediaItem(MediaKind.SHOW, s.id, s.title, "${s.year} · $episodes", s.images.posterUrl(settings.sonarrUrl), s.tmdbId)
            }
    }

    /**
     * When each show's newest episode file was imported, like Plex's "Last Episode Date Added". Sonarr's series
     * list doesn't say, so this asks for each show's files. A show whose files can't be fetched is left out.
     */
    private suspend fun lastEpisodeAdded(series: List<SonarrSeries>): Map<Int, String> = coroutineScope {
        series
            .filter { (it.statistics?.episodeFileCount ?: 0) > 0 }
            .map { s -> async { s.id to runCatching { sonarr.episodeFiles(s.id).mapNotNull { it.dateAdded }.maxOrNull() }.getOrNull() } }
            .awaitAll()
            .mapNotNull { (id, date) -> date?.let { id to it } }
            .toMap()
    }

    suspend fun search(kind: MediaKind, term: String): List<SearchResult> {
        val results = when (kind) {
            MediaKind.MOVIE -> radarr.lookup(term)
            MediaKind.SHOW -> sonarr.lookup(term)
        }
        return results.take(MAX_RESULTS).mapIndexed { index, json -> json.toSearchResult(kind, index) }
    }

    /**
     * Looks up a popular title in Radarr/Sonarr so it can be added. Sonarr can't map every TMDB id to its
     * own TVDB ids, so if that finds nothing, it falls back to searching by name.
     */
    suspend fun lookup(title: PopularTitle): SearchResult? {
        val byId = when (title.kind) {
            MediaKind.MOVIE -> radarr.lookup("tmdb:${title.tmdbId}")
            MediaKind.SHOW -> sonarr.lookup("tmdb:${title.tmdbId}")
        }
        val match = byId.firstOrNull() ?: when (title.kind) {
            MediaKind.MOVIE -> radarr.lookup(title.title)
            MediaKind.SHOW -> sonarr.lookup(title.title)
        }.let { results ->
            results.firstOrNull { it.int("tmdbId") == title.tmdbId }
                ?: results.firstOrNull { it.string("title").equals(title.title, ignoreCase = true) && it.int("year") == title.year }
        }
        return match?.toSearchResult(title.kind, 0)?.copy(key = title.key)
    }

    suspend fun qualityProfiles(kind: MediaKind): List<QualityProfile> = when (kind) {
        MediaKind.MOVIE -> radarr.qualityProfiles()
        MediaKind.SHOW -> sonarr.qualityProfiles()
    }

    /**
     * Adds the result to Radarr/Sonarr as monitored and starts a search for it straight away.
     * For a show, only [seasons] are monitored and searched. New seasons are always monitored.
     */
    suspend fun add(result: SearchResult, qualityProfileId: Int, seasons: Set<Int> = emptySet()) {
        when (result.kind) {
            MediaKind.MOVIE -> {
                val root = radarr.rootFolders().first().path
                radarr.add(result.raw.with {
                    put("qualityProfileId", qualityProfileId)
                    put("rootFolderPath", root)
                    put("monitored", true)
                    put("minimumAvailability", "released")
                    putJsonObject("addOptions") { put("searchForMovie", true) }
                })
            }
            MediaKind.SHOW -> {
                val root = sonarr.rootFolders().first().path
                sonarr.add(result.raw.with {
                    put("qualityProfileId", qualityProfileId)
                    put("rootFolderPath", root)
                    put("monitored", true)
                    put("seasonFolder", true)
                    put("monitorNewItems", "all")
                    putJsonArray("seasons") {
                        result.seasons.forEach { number ->
                            addJsonObject {
                                put("seasonNumber", number)
                                put("monitored", number in seasons)
                            }
                        }
                    }
                    // "skip" tells Sonarr to use the seasons' monitored flags instead of a preset.
                    putJsonObject("addOptions") {
                        put("monitor", "skip")
                        put("searchForMissingEpisodes", seasons.isNotEmpty())
                    }
                })
            }
        }
    }

    /** Everything Radarr or Sonarr is currently downloading or waiting to import. */
    suspend fun queue(kind: MediaKind): List<QueueItem> = when (kind) {
        MediaKind.MOVIE -> radarr.queue().records.toQueueItems(kind, settings.radarrUrl)
        MediaKind.SHOW -> sonarr.queue().records.toQueueItems(kind, settings.sonarrUrl)
    }

    /**
     * Removes a download from the queue and the download client. With [blocklist], the release is
     * never grabbed again and Radarr/Sonarr search for a different one.
     */
    suspend fun removeFromQueue(item: QueueItem, blocklist: Boolean) = when (item.kind) {
        MediaKind.MOVIE -> radarr.removeFromQueue(QueueIds(item.ids), blocklist)
        MediaKind.SHOW -> sonarr.removeFromQueue(QueueIds(item.ids), blocklist)
    }

    suspend fun details(item: MediaItem): LibraryDetails = when (item.kind) {
        MediaKind.MOVIE -> radarr.movie(item.id).let { LibraryDetails.Movie(it, it.decode<MovieDetails>()) }
        MediaKind.SHOW -> coroutineScope {
            val episodes = async { sonarr.episodes(item.id) }
            val raw = sonarr.seriesById(item.id)
            LibraryDetails.Show(raw, raw.decode<SeriesDetails>(), episodes.await())
        }
    }

    /** Asks Radarr/Sonarr to search the indexers now and grab the best release for anything missing. */
    suspend fun searchIndexers(details: LibraryDetails) = when (details) {
        is LibraryDetails.Movie -> radarr.command(buildJsonObject {
            put("name", "MoviesSearch")
            putJsonArray("movieIds") { add(details.movie.id) }
        })
        is LibraryDetails.Show -> sonarr.command(buildJsonObject {
            put("name", "SeriesSearch")
            put("seriesId", details.series.id)
        })
    }

    suspend fun searchSeason(show: LibraryDetails.Show, seasonNumber: Int) = sonarr.command(buildJsonObject {
        put("name", "SeasonSearch")
        put("seriesId", show.series.id)
        put("seasonNumber", seasonNumber)
    })

    suspend fun searchEpisode(episode: SonarrEpisode) = sonarr.command(buildJsonObject {
        put("name", "EpisodeSearch")
        putJsonArray("episodeIds") { add(episode.id) }
    })

    suspend fun setMonitored(details: LibraryDetails, monitored: Boolean) = update(details) { put("monitored", monitored) }

    suspend fun setQualityProfile(details: LibraryDetails, profileId: Int) =
        update(details) { put("qualityProfileId", profileId) }

    /** Sonarr also flips every episode in the season to match. */
    suspend fun setSeasonMonitored(show: LibraryDetails.Show, seasonNumber: Int, monitored: Boolean) {
        val seasons = show.raw["seasons"]?.jsonArray.orEmpty().map { element ->
            val season = element.jsonObject
            if (season.int("seasonNumber") == seasonNumber) season.with { put("monitored", monitored) } else season
        }
        update(show) { put("seasons", JsonArray(seasons)) }
    }

    suspend fun setEpisodeMonitored(episode: SonarrEpisode, monitored: Boolean) =
        sonarr.monitorEpisodes(EpisodeMonitor(listOf(episode.id), monitored))

    /** Removes it from Radarr/Sonarr. With [deleteFiles], its files go too, so it disappears from Plex. */
    suspend fun delete(details: LibraryDetails, deleteFiles: Boolean) = when (details) {
        is LibraryDetails.Movie -> radarr.deleteMovie(details.movie.id, deleteFiles)
        is LibraryDetails.Show -> sonarr.deleteSeries(details.series.id, deleteFiles)
    }

    /** PUTs the record back with [change] applied. Radarr and Sonarr only accept the whole record. */
    private suspend fun update(details: LibraryDetails, change: JsonObjectBuilder.() -> Unit) = when (details) {
        is LibraryDetails.Movie -> radarr.updateMovie(details.movie.id, details.raw.with(change))
        is LibraryDetails.Show -> sonarr.updateSeries(details.series.id, details.raw.with(change))
    }

    private inline fun <reified T> JsonObject.decode(): T = ArrClient.json.decodeFromJsonElement(this)

    private fun JsonObject.toSearchResult(kind: MediaKind, index: Int): SearchResult {
        val year = int("year") ?: 0
        val inLibrary = (int("id") ?: 0) > 0
        val detail = when {
            inLibrary -> "In library"
            kind == MediaKind.SHOW -> seasonCount()?.let { if (it == 1) "1 season" else "$it seasons" }
            else -> int("runtime")?.let(::formatRuntime)
        }
        // Sonarr reports tmdbId as 0 for many shows, so use the id each service actually keys on,
        // plus the index, which keeps the key unique even if an id is missing.
        val externalId = string(if (kind == MediaKind.MOVIE) "tmdbId" else "tvdbId")
        return SearchResult(
            kind = kind,
            key = "${kind.name}-$index-$externalId",
            title = string("title") ?: "Unknown",
            subtitle = listOfNotNull(year.takeIf { it > 0 }?.toString(), detail).joinToString(" · "),
            posterUrl = (string("remotePoster") ?: posterFromImages())?.let(::smallerPoster),
            overview = string("overview") ?: "",
            inLibrary = inLibrary,
            seasons = this["seasons"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.int("seasonNumber") }.sorted(),
            raw = this,
        )
    }

    private fun JsonObject.seasonCount(): Int? =
        int("seasonCount") ?: (this["statistics"] as? JsonObject)?.int("seasonCount")

    private fun JsonObject.posterFromImages(): String? =
        this["images"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { (it["coverType"] as? JsonPrimitive)?.contentOrNull == "poster" }
            ?.let { (it["remoteUrl"] as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.with(extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        this@with.forEach { (k, v) -> put(k, v) }
        extra()
    }

    private companion object {
        const val MAX_RESULTS = 30
    }
}
