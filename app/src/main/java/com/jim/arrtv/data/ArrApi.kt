package com.jim.arrtv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
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
    @GET("api/v3/queue?page=1&pageSize=500&includeSeries=true&includeEpisode=true")
    suspend fun queue(): QueuePage

    @HTTP(method = "DELETE", path = "api/v3/queue/bulk?removeFromClient=true", hasBody = true)
    suspend fun removeFromQueue(@Body ids: QueueIds, @Query("blocklist") blocklist: Boolean)
}

@Serializable
data class ArrImage(val coverType: String, val remoteUrl: String? = null)

@Serializable
data class RadarrMovie(
    val id: Int,
    val title: String,
    val sortTitle: String? = null,
    val year: Int = 0,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val images: List<ArrImage> = emptyList(),
)

@Serializable
data class SonarrSeries(
    val id: Int,
    val title: String,
    val sortTitle: String? = null,
    val year: Int = 0,
    val images: List<ArrImage> = emptyList(),
    val statistics: SeriesStatistics? = null,
)

@Serializable
data class SeriesStatistics(val episodeFileCount: Int = 0, val episodeCount: Int = 0)

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

fun List<ArrImage>.posterUrl() = firstOrNull { it.coverType == "poster" }?.remoteUrl

object ArrClient {
    private val json = Json { ignoreUnknownKeys = true }

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
