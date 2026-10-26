package com.jim.arrtv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Retrofit doesn't allow service interfaces to extend each other, so Radarr and Sonarr each get their own.
// Lookup results stay as raw JSON because the add endpoints expect them posted back almost unchanged.

interface RadarrApi {
    @GET("api/v3/movie")
    suspend fun movies(): List<RadarrMovie>

    @GET("api/v3/movie/lookup")
    suspend fun lookup(@Query("term") term: String): List<JsonObject>

    @POST("api/v3/movie")
    suspend fun add(@Body movie: JsonObject): JsonObject

    @GET("api/v3/qualityprofile")
    suspend fun qualityProfiles(): List<QualityProfile>

    @GET("api/v3/rootfolder")
    suspend fun rootFolders(): List<RootFolder>

    @GET("api/v3/movie/{id}")
    suspend fun movie(@Path("id") id: Int): JsonObject

    @PUT("api/v3/movie/{id}?moveFiles=false")
    suspend fun updateMovie(@Path("id") id: Int, @Body movie: JsonObject)

    @DELETE("api/v3/movie/{id}?addImportExclusion=false")
    suspend fun deleteMovie(@Path("id") id: Int, @Query("deleteFiles") deleteFiles: Boolean)

    @POST("api/v3/command")
    suspend fun command(@Body command: JsonObject)

    @GET("api/v3/queue?page=1&pageSize=500&includeMovie=true")
    suspend fun queue(): QueuePage

    @HTTP(method = "DELETE", path = "api/v3/queue/bulk?removeFromClient=true", hasBody = true)
    suspend fun removeFromQueue(@Body ids: QueueIds, @Query("blocklist") blocklist: Boolean)
}

interface SonarrApi {
    @GET("api/v3/series")
    suspend fun series(): List<SonarrSeries>

    @GET("api/v3/series/lookup")
    suspend fun lookup(@Query("term") term: String): List<JsonObject>

    @POST("api/v3/series")
    suspend fun add(@Body series: JsonObject): JsonObject

    @GET("api/v3/qualityprofile")
    suspend fun qualityProfiles(): List<QualityProfile>

    @GET("api/v3/rootfolder")
    suspend fun rootFolders(): List<RootFolder>

    @GET("api/v3/series/{id}")
    suspend fun seriesById(@Path("id") id: Int): JsonObject

    @PUT("api/v3/series/{id}?moveFiles=false")
    suspend fun updateSeries(@Path("id") id: Int, @Body series: JsonObject)

    @DELETE("api/v3/series/{id}?addImportListExclusion=false")
    suspend fun deleteSeries(@Path("id") id: Int, @Query("deleteFiles") deleteFiles: Boolean)

    /** Sonarr only honours one seriesId per request. */
    @GET("api/v3/episodefile")
    suspend fun episodeFiles(@Query("seriesId") seriesId: Int): List<FileDate>

    @GET("api/v3/episode?includeEpisodeFile=true")
    suspend fun episodes(@Query("seriesId") seriesId: Int): List<SonarrEpisode>

    @PUT("api/v3/episode/monitor")
    suspend fun monitorEpisodes(@Body body: EpisodeMonitor)

    @POST("api/v3/command")
    suspend fun command(@Body command: JsonObject)

    @GET("api/v3/queue?page=1&pageSize=500&includeSeries=true&includeEpisode=true")
    suspend fun queue(): QueuePage

    @HTTP(method = "DELETE", path = "api/v3/queue/bulk?removeFromClient=true", hasBody = true)
    suspend fun removeFromQueue(@Body ids: QueueIds, @Query("blocklist") blocklist: Boolean)
}

@Serializable
data class ArrImage(val coverType: String, val url: String? = null, val remoteUrl: String? = null)

@Serializable
data class RadarrMovie(
    val id: Int,
    val title: String,
    val sortTitle: String? = null,
    val year: Int = 0,
    val tmdbId: Int = 0,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val images: List<ArrImage> = emptyList(),
    /** When it was added to Radarr, as an ISO timestamp. */
    val added: String? = null,
    val movieFile: FileDate? = null,
)

/** Just the date a movie or episode file was imported, as an ISO timestamp. */
@Serializable
data class FileDate(val dateAdded: String? = null)

@Serializable
data class SonarrSeries(
    val id: Int,
    val title: String,
    val sortTitle: String? = null,
    val year: Int = 0,
    val tmdbId: Int = 0,
    val images: List<ArrImage> = emptyList(),
    val statistics: SeriesStatistics? = null,
    /** When it was added to Sonarr, as an ISO timestamp. */
    val added: String? = null,
)

@Serializable
data class SeriesStatistics(val episodeFileCount: Int = 0, val episodeCount: Int = 0, val sizeOnDisk: Double = 0.0)

@Serializable
data class QualityProfile(val id: Int, val name: String)

@Serializable
data class RootFolder(val path: String)

@Serializable
data class QueuePage(val records: List<QueueRecord> = emptyList())

/** One queue entry. Sonarr makes one per episode, so a season pack is several records sharing a downloadId. */
@Serializable
data class QueueRecord(
    val id: Int,
    val movieId: Int? = null,
    val seriesId: Int? = null,
    val downloadId: String? = null,
    val title: String? = null,
    val status: String? = null,
    val trackedDownloadStatus: String? = null,
    val trackedDownloadState: String? = null,
    val size: Double = 0.0,
    val sizeleft: Double = 0.0,
    val timeleft: String? = null,
    val errorMessage: String? = null,
    val statusMessages: List<QueueStatusMessage> = emptyList(),
    val quality: QueueQuality? = null,
    val downloadClient: String? = null,
    val indexer: String? = null,
    val movie: QueueMedia? = null,
    val series: QueueMedia? = null,
    val episode: QueueEpisode? = null,
)

@Serializable
data class QueueStatusMessage(val title: String? = null, val messages: List<String> = emptyList())

@Serializable
data class QueueQuality(val quality: Named? = null)

@Serializable
data class Named(val name: String)

@Serializable
data class QueueMedia(val title: String, val year: Int = 0, val images: List<ArrImage> = emptyList())

@Serializable
data class QueueEpisode(val seasonNumber: Int, val episodeNumber: Int, val title: String? = null)

@Serializable
data class QueueIds(val ids: List<Int>)

/**
 * The poster for something already in Radarr/Sonarr. They keep 500px copies on the server, which load far
 * faster than the full-size originals (often 1.5 MB each) and are on the local network.
 */
fun List<ArrImage>.posterUrl(serverUrl: String): String? {
    val poster = firstOrNull { it.coverType == "poster" } ?: return null
    poster.url?.takeIf { it.startsWith("/") }?.let {
        return serverUrl.trim().trimEnd('/') + it.replace("/poster.", "/poster-500.")
    }
    return poster.remoteUrl?.let(::smallerPoster)
}

/** Asks TMDB for its 500px size instead of the full-size original. */
fun smallerPoster(url: String) = url.replace("image.tmdb.org/t/p/original/", "image.tmdb.org/t/p/w500/")

object ArrClient {
    val json = Json { ignoreUnknownKeys = true }

    fun <T> create(baseUrl: String, apiKey: String, service: Class<T>): T {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Api-Key", apiKey).build())
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl.trim().trimEnd('/') + "/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(service)
    }
}
