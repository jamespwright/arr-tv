package com.jim.arrtv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * TMDB's discover API, for the popular titles on the Search tab. Radarr and Sonarr both look titles up by
 * TMDB id ("tmdb:603"), so anything picked here goes straight to the normal add page.
 */
interface TmdbApi {
    // Release types 4 and 5 are digital and physical, so only movies that can actually be downloaded show up.
    @GET("3/discover/movie?sort_by=popularity.desc&include_adult=false&with_release_type=4|5&vote_count.gte=50")
    suspend fun movies(
        @Query("primary_release_year") year: Int?,
        @Query("vote_average.gte") minRating: Int?,
        @Query("release_date.lte") releasedBy: String,
        @Query("page") page: Int,
    ): TmdbPage

    // Leaves out news, soaps and talk shows, which otherwise fill the top of the list.
    @GET("3/discover/tv?sort_by=popularity.desc&include_adult=false&without_genres=10763|10766|10767&vote_count.gte=50")
    suspend fun shows(
        @Query("first_air_date_year") year: Int?,
        @Query("vote_average.gte") minRating: Int?,
        @Query("first_air_date.lte") airedBy: String,
        @Query("page") page: Int,
    ): TmdbPage
}

@Serializable
data class TmdbPage(val results: List<TmdbTitle> = emptyList())

/** Movies have [title] and [releaseDate]. Shows have [name] and [firstAirDate]. */
@Serializable
data class TmdbTitle(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val overview: String? = null,
)

/** A popular movie or show from TMDB. [inLibrary] is filled in from the Radarr/Sonarr library. */
data class PopularTitle(
    val kind: MediaKind,
    val tmdbId: Int,
    override val title: String,
    val year: Int?,
    val rating: Double,
    val overview: String,
    val posterPath: String?,
    val inLibrary: Boolean = false,
) : Poster {
    override val key get() = "popular-${kind.name}-$tmdbId"
    override val subtitle
        get() = listOfNotNull(
            year?.toString(),
            if (inLibrary) "In library" else rating.takeIf { it > 0 }?.let { "★ %.1f".format(Locale.US, it) },
        ).joinToString(" · ")
    override val posterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val largePosterUrl get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
}

/**
 * Which popular list to show. [yearsAgo] of 0 is this year, 1 is last year, and null is any year.
 * [minRating] is out of 10, or null for any rating.
 */
data class PopularQuery(val kind: MediaKind, val yearsAgo: Int?, val minRating: Int?)

class TmdbRepository(private val key: String) {
    private val api by lazy { create(key) }

    val isConfigured get() = key.isNotBlank()

    suspend fun popular(query: PopularQuery): List<PopularTitle> = coroutineScope {
        val year = query.yearsAgo?.let { Calendar.getInstance().get(Calendar.YEAR) - it }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val pages = (1..PAGES).map { page ->
            async {
                when (query.kind) {
                    MediaKind.MOVIE -> api.movies(year, query.minRating, today, page)
                    MediaKind.SHOW -> api.shows(year, query.minRating, today, page)
                }
            }
        }.awaitAll()
        pages.flatMap { it.results }
            .distinctBy { it.id } // a title can slip onto the next page if popularity shifts mid-load
            .map { t ->
                PopularTitle(
                    kind = query.kind,
                    tmdbId = t.id,
                    title = t.title ?: t.name ?: "Unknown",
                    year = (t.releaseDate ?: t.firstAirDate)?.take(4)?.toIntOrNull(),
                    rating = t.voteAverage,
                    overview = t.overview.orEmpty(),
                    posterPath = t.posterPath,
                )
            }
    }

    private companion object {
        /** TMDB returns 20 titles a page. */
        const val PAGES = 3

        /**
         * Takes either kind of TMDB credential: the short "API key" or the long "API read access token"
         * (which starts with "eyJ").
         */
        fun create(key: String): TmdbApi {
            val trimmed = key.trim()
            val http = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val authed = if (trimmed.startsWith("eyJ")) {
                        request.newBuilder().header("Authorization", "Bearer $trimmed").build()
                    } else {
                        request.newBuilder().url(request.url.newBuilder().addQueryParameter("api_key", trimmed).build()).build()
                    }
                    chain.proceed(authed)
                }
                .build()
            return Retrofit.Builder()
                .baseUrl("https://api.themoviedb.org/")
                .client(http)
                .addConverterFactory(ArrClient.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TmdbApi::class.java)
        }
    }
}
