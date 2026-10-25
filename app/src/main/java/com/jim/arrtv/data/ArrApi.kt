package com.jim.arrtv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

interface RadarrApi {
    @GET("api/v3/movie")
    suspend fun movies(): List<RadarrMovie>
}

interface SonarrApi {
    @GET("api/v3/series")
    suspend fun series(): List<SonarrSeries>
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
