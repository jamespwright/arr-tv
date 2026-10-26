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
import retrofit2.http.Path
import retrofit2.http.QueryMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TMDB's discover API, for the popular titles on the Search tab. Radarr and Sonarr both look titles up by
 * TMDB id ("tmdb:603"), so anything picked here goes straight to the normal add page.
 */
interface TmdbApi {
    /** The parameters are built by [TmdbRepository.popular], from the Search tab's filters. */
    @GET("3/discover/movie?include_adult=false")
    suspend fun discoverMovies(@QueryMap params: Map<String, String>): TmdbPage

    // Leaves out news, soaps and talk shows, which otherwise fill the top of the list.
    @GET("3/discover/tv?include_adult=false&without_genres=10763|10766|10767")
    suspend fun discoverShows(@QueryMap params: Map<String, String>): TmdbPage

    @GET("3/movie/{id}/release_dates")
    suspend fun releaseDates(@Path("id") id: Int): TmdbReleaseDates

    @GET("3/movie/{id}/videos")
    suspend fun movieVideos(@Path("id") id: Int): TmdbVideos

    @GET("3/tv/{id}/videos")
    suspend fun showVideos(@Path("id") id: Int): TmdbVideos
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
    val popularity: Double = 0.0,
    val overview: String? = null,
)

/** A movie's releases in each country. Types 1-3 are premieres and cinemas, 4 is digital and 5 is physical. */
@Serializable
data class TmdbReleaseDates(val results: List<TmdbCountryReleases> = emptyList())

@Serializable
data class TmdbCountryReleases(@SerialName("release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList())

@Serializable
data class TmdbReleaseDate(val type: Int, @SerialName("release_date") val releaseDate: String? = null)

@Serializable
data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbVideo(val key: String, val site: String, val type: String, val name: String = "", val official: Boolean = false)

/** A popular movie or show from TMDB. [inLibrary] is filled in from the Radarr/Sonarr library. */
data class PopularTitle(
    val kind: MediaKind,
    val tmdbId: Int,
    override val title: String,
    /** The release or first air date, as yyyy-MM-dd. */
    val date: String?,
    val rating: Double,
    val overview: String,
    val posterPath: String?,
    val inLibrary: Boolean = false,
    /** For movies: whether it's out on streaming yet. Null for shows. */
    override val release: Release? = null,
) : Poster {
    val year get() = date?.take(4)?.toIntOrNull()
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
 * How the popular titles are arranged. This only reorders the list: it's always the most popular titles that
 * match the filters, so choosing "Top rated" doesn't swap in a different set.
 */
enum class PopularSort(val label: String) {
    POPULAR("Most popular"),
    RATING("Top rated"),
    NEWEST("Newest"),
    TITLE("Title A\u2013Z"),
}

/** The titles in [sort]'s order. They arrive most popular first, which also breaks ties. */
fun List<PopularTitle>.arrangedBy(sort: PopularSort): List<PopularTitle> = when (sort) {
    PopularSort.POPULAR -> this
    PopularSort.RATING -> sortedByDescending { it.rating }
    PopularSort.NEWEST -> sortedByDescending { it.date.orEmpty() }
    PopularSort.TITLE -> sortedBy { it.title.lowercase().removePrefix("the ") }
}

/**
 * A subscription streaming service. TMDB only knows what's on each service country by country, so the
 * [regions] it's in are all checked and the lists merged. [providers] are TMDB's ids for it, any of which
 * counts (Prime Video has a different id in some countries, and Paramount+ has a tier per id).
 */
enum class StreamingPlatform(val label: String, val providers: String, val regions: List<String>) {
    NETFLIX("Netflix", "8", listOf("US", "GB", "AU", "CA")),
    PRIME("Prime Video", "9|119", listOf("US", "GB", "AU", "CA")),
    DISNEY("Disney+", "337", listOf("US", "GB", "AU", "CA")),
    APPLE("Apple TV+", "350", listOf("US", "GB", "AU", "CA")),
    MAX("HBO Max", "1899", listOf("US", "GB", "AU")),
    PARAMOUNT("Paramount+", "531|2303|2616", listOf("US", "GB", "AU", "CA")),
    STAN("Stan", "21", listOf("AU")),
    BINGE("Binge", "385", listOf("AU")),
    HULU("Hulu", "15", listOf("US")),
    PEACOCK("Peacock", "386|387", listOf("US")),
    CRUNCHYROLL("Crunchyroll", "283", listOf("US", "GB", "AU", "CA")),
}

/** TMDB's genres, which have different ids for movies and shows. News, soaps and talk shows are left out. */
fun tmdbGenres(kind: MediaKind): List<Pair<Int, String>> = when (kind) {
    MediaKind.MOVIE -> listOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi", 53 to "Thriller", 10752 to "War", 37 to "Western",
    )
    MediaKind.SHOW -> listOf(
        10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 10762 to "Kids", 9648 to "Mystery", 10764 to "Reality",
        10765 to "Sci-Fi & Fantasy", 10768 to "War & Politics", 37 to "Western",
    )
}

/**
 * Which popular list to show. [fromYear] keeps titles released (or first aired) that year or later, and
 * [minRating] those rated at least that out of 10. [streamingOnly] leaves out movies still in cinemas or not
 * out yet (it's always false for shows). [genre] is a TMDB genre id from [tmdbGenres], and [platform] limits
 * the list to one streaming service. Null means any.
 */
data class PopularQuery(
    val kind: MediaKind,
    val fromYear: Int?,
    val minRating: Int?,
    val streamingOnly: Boolean,
    val genre: Int? = null,
    val platform: StreamingPlatform? = null,
)

class TmdbRepository(private val key: String) {
    private val api by lazy { create(key) }

    val isConfigured get() = key.isNotBlank()

    suspend fun popular(query: PopularQuery): List<PopularTitle> = coroutineScope {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val regions = query.platform?.regions ?: listOf(null)
        val pages = regions.flatMap { region ->
            (1..PAGES).map { page ->
                async {
                    val params = discoverParams(query, region, today, page)
                    if (query.kind == MediaKind.SHOW) api.discoverShows(params) else api.discoverMovies(params)
                }
            }
        }.awaitAll()
        // A title can slip onto the next page if popularity shifts mid-load, or be on the service in several countries.
        var titles = pages.flatMap { it.results }.distinctBy { it.id }
        // Each country's list is already most popular first, but merged ones need sorting again.
        if (regions.size > 1) titles = titles.sortedByDescending { it.popularity }.take(PAGES * PAGE_SIZE)
        // Discover can't say where each movie is in its release, so for the full list that's asked per movie.
        val releases = when {
            query.kind == MediaKind.SHOW -> emptyMap()
            query.streamingOnly -> titles.associate { it.id to Release.Streaming }
            else -> titles.map { t -> async { t.id to runCatching { release(t.id) }.getOrNull() } }.awaitAll().toMap()
        }
        titles.map { t ->
            PopularTitle(
                kind = query.kind,
                tmdbId = t.id,
                title = t.title ?: t.name ?: "Unknown",
                date = (t.releaseDate ?: t.firstAirDate)?.takeIf { it.isNotBlank() },
                rating = t.voteAverage,
                overview = t.overview.orEmpty(),
                posterPath = t.posterPath,
                release = releases[t.id],
            )
        }
    }

    /** The discover filters for one page of [query], in one country when it's limited to a streaming service. */
    private fun discoverParams(query: PopularQuery, region: String?, today: String, page: Int): Map<String, String> {
        val from = query.fromYear?.let { "$it-01-01" }
        val movie = query.kind == MediaKind.MOVIE
        // A rating from a handful of votes means nothing, so rating filters need [RATED_VOTES]. Otherwise only a
        // few are asked for: enough to drop near-unknown titles, but not popular ones that few people rate (The
        // Secret Lives of Mormon Wives has 40). Movies not out yet have hardly any, so the full movie list asks for none.
        val minVotes = when {
            query.minRating != null -> RATED_VOTES
            movie && !query.streamingOnly -> 0
            else -> MIN_VOTES
        }
        return buildMap {
            put("sort_by", "popularity.desc")
            put("page", page.toString())
            put("vote_count.gte", minVotes.toString())
            query.minRating?.let { put("vote_average.gte", it.toString()) }
            query.genre?.let { put("with_genres", it.toString()) }
            if (movie) {
                from?.let { put("primary_release_date.gte", it) }
                // Release types 4 and 5 are digital and physical, so only movies that can actually be downloaded show up.
                if (query.streamingOnly) {
                    put("with_release_type", "4|5")
                    put("release_date.lte", today)
                }
            } else {
                from?.let { put("first_air_date.gte", it) }
                put("first_air_date.lte", today)
            }
            if (query.platform != null && region != null) {
                put("with_watch_providers", query.platform.providers)
                put("watch_region", region)
                put("with_watch_monetization_types", "flatrate")
            }
        }
    }

    /** The first cinema and first digital or physical release anywhere in the world. */
    suspend fun release(movieId: Int): Release {
        val dates = api.releaseDates(movieId).results.flatMap { it.releaseDates }
        fun first(vararg types: Int) = dates.filter { it.type in types }.mapNotNull { parseUtc(it.releaseDate) }.minOrNull()
        return Release.of(cinemas = first(2, 3) ?: first(1), streaming = first(4, 5))
    }

    /**
     * YouTube trailers, then teasers. Official ones come first, and among those, ones actually called a trailer
     * (TMDB files sneak peeks and TV spots as trailers too).
     */
    suspend fun trailers(kind: MediaKind, id: Int): List<String> {
        val videos = when (kind) {
            MediaKind.MOVIE -> api.movieVideos(id)
            MediaKind.SHOW -> api.showVideos(id)
        }.results.filter { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }
        return videos.sortedWith(
            compareBy({ it.type != "Trailer" }, { !it.official }, { !it.name.contains("trailer", ignoreCase = true) }),
        ).map { it.key }
    }

    private companion object {
        /** TMDB returns 20 titles a page. */
        const val PAGES = 3
        const val PAGE_SIZE = 20

        /** The fewest votes for a title to show up at all, which keeps out near-unknown ones. */
        const val MIN_VOTES = 10

        /** The fewest votes for a rating to count, when filtering by rating. */
        const val RATED_VOTES = 50

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
