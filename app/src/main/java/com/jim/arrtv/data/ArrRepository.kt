package com.jim.arrtv.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

data class MediaItem(
    override val key: String,
    override val title: String,
    override val subtitle: String,
    override val posterUrl: String?,
) : Poster

data class SearchResult(
    val kind: MediaKind,
    override val key: String,
    override val title: String,
    override val subtitle: String,
    override val posterUrl: String?,
    val overview: String,
    val inLibrary: Boolean,
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
        MediaKind.MOVIE -> radarr.movies()
            .sortedBy { (it.sortTitle ?: it.title).lowercase() }
            .map { m ->
                val status = when {
                    m.hasFile -> "Downloaded"
                    m.monitored -> "Wanted"
                    else -> "Not monitored"
                }
                MediaItem("movie-${m.id}", m.title, "${m.year} · $status", m.images.posterUrl())
            }
        MediaKind.SHOW -> sonarr.series()
            .sortedBy { (it.sortTitle ?: it.title).lowercase() }
            .map { s ->
                val episodes = s.statistics?.let { "${it.episodeFileCount}/${it.episodeCount} episodes" } ?: ""
                MediaItem("show-${s.id}", s.title, "${s.year} · $episodes", s.images.posterUrl())
            }
    }

    suspend fun search(kind: MediaKind, term: String): List<SearchResult> {
        val results = when (kind) {
            MediaKind.MOVIE -> radarr.lookup(term)
            MediaKind.SHOW -> sonarr.lookup(term)
        }
        return results.take(MAX_RESULTS).mapIndexed { index, json -> json.toSearchResult(kind, index) }
    }

    suspend fun qualityProfiles(kind: MediaKind): List<QualityProfile> = when (kind) {
        MediaKind.MOVIE -> radarr.qualityProfiles()
        MediaKind.SHOW -> sonarr.qualityProfiles()
    }

    /** Adds the result to Radarr/Sonarr as monitored and starts a search for it straight away. */
    suspend fun add(result: SearchResult, qualityProfileId: Int) {
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
                    putJsonObject("addOptions") {
                        put("monitor", "all")
                        put("searchForMissingEpisodes", true)
                    }
                })
            }
        }
    }

    private fun JsonObject.toSearchResult(kind: MediaKind, index: Int): SearchResult {
        val year = int("year") ?: 0
        val inLibrary = (int("id") ?: 0) > 0
        val detail = when {
            inLibrary -> "In library"
            kind == MediaKind.SHOW -> seasonCount()?.let { if (it == 1) "1 season" else "$it seasons" }
            else -> int("runtime")?.takeIf { it > 0 }?.let { if (it < 60) "${it}m" else "${it / 60}h ${it % 60}m" }
        }
        // Sonarr reports tmdbId as 0 for many shows, so use the id each service actually keys on,
        // plus the index, which keeps the key unique even if an id is missing.
        val externalId = string(if (kind == MediaKind.MOVIE) "tmdbId" else "tvdbId")
        return SearchResult(
            kind = kind,
            key = "${kind.name}-$index-$externalId",
            title = string("title") ?: "Unknown",
            subtitle = listOfNotNull(year.takeIf { it > 0 }?.toString(), detail).joinToString(" · "),
            posterUrl = string("remotePoster") ?: posterFromImages(),
            overview = string("overview") ?: "",
            inLibrary = inLibrary,
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
