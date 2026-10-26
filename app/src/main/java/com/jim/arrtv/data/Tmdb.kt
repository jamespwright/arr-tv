package com.jim.arrtv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.QueryMap
import java.io.IOException
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Calendar
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

    // Leaves out news, soaps and talk shows, which otherwise fill the top of the list, and shows not made in
    // English, which are mostly little-known dramas rated 9 or 10 by a handful of people.
    @GET("3/discover/tv?include_adult=false&without_genres=10763|10766|10767&with_original_language=en")
    suspend fun discoverShows(@QueryMap params: Map<String, String>): TmdbPage

    @GET("3/movie/{id}/release_dates")
    suspend fun releaseDates(@Path("id") id: Int): TmdbReleaseDates

    @GET("3/movie/{id}/videos")
    suspend fun movieVideos(@Path("id") id: Int): TmdbVideos

    @GET("3/tv/{id}/videos")
    suspend fun showVideos(@Path("id") id: Int): TmdbVideos
}

@Serializable
data class TmdbPage(
    val results: List<TmdbTitle> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
)

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
    @SerialName("vote_count") val voteCount: Int = 0,
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
    val votes: Int,
    val popularity: Double,
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
 * How the popular titles are arranged. Most popular is paged straight from TMDB. The others need the whole
 * list on the TV (see [TmdbRepository.all]): TMDB sorts names by their original language ("Dudes" is under A,
 * for Alphamännchen) and can't weigh ratings by votes.
 */
enum class PopularSort(val label: String) {
    POPULAR("Most popular"),
    RATING("Top rated"),
    NEWEST("Newest"),
    TITLE("Title A\u2013Z"),
}

/**
 * The titles in [sort]'s order. Top rated goes by the rating shown on the poster, with the most voted first
 * among equals. Newest leaves out what isn't out yet.
 */
fun List<PopularTitle>.arrangedBy(sort: PopularSort): List<PopularTitle> = when (sort) {
    PopularSort.POPULAR -> sortedByDescending { it.popularity }
    PopularSort.RATING -> sortedWith(compareByDescending<PopularTitle> { it.rating }.thenByDescending { it.votes })
    PopularSort.NEWEST -> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        filterNot { it.date.orEmpty() > today }.sortedByDescending { it.date.orEmpty() }
    }
    PopularSort.TITLE -> {
        val collator = Collator.getInstance(Locale.US).apply { strength = Collator.PRIMARY }
        map { it to collator.getCollationKey(sortName(it.title)) }
            .sortedWith(compareBy({ it.second }, { it.first.year }))
            .map { it.first }
    }
}

/** A title as it's filed A–Z: without "The", and without leading punctuation ("'Allo 'Allo!" is under A). */
private fun sortName(title: String): String {
    val name = title.lowercase(Locale.US).removePrefix("the ").trimStart { !it.isLetterOrDigit() }
    return name.ifEmpty { title.lowercase(Locale.US) }
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
 * Which popular list to show. [fromYear] keeps movies released that year or later, and shows with an episode aired that year or later, and
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
    /** For shows: the year they must have first aired in or after, separate from [fromYear] (their latest episode). */
    val startedYear: Int? = null,
    val sort: PopularSort = PopularSort.POPULAR,
) {
    /** Whether the whole list is loaded and arranged on the TV, rather than paged from TMDB in its order. */
    val loadsAll get() = sort != PopularSort.POPULAR || platform != null

    /** The whole list doesn't depend on how it's arranged, so every arrangement shares the one under this key. */
    val allKey get() = copy(sort = PopularSort.POPULAR)
}

/** Shown instead of a list too big to load whole. */
class TooManyTitles(kind: MediaKind) : Exception(
    "There are over ${"%,d".format(Locale.US, TmdbRepository.MAX_ALL)} ${kind.label.lowercase()} to arrange. " +
        "Choose a genre, platform or year to narrow them down, or arrange by most popular.",
)

/** A batch of popular titles, and whether TMDB has more pages after it. */
data class PopularBatch(val titles: List<PopularTitle>, val hasMore: Boolean)

class TmdbRepository(private val key: String) {
    private val api by lazy { create(key) }

    val isConfigured get() = key.isNotBlank()

    /**
     * One batch of the most popular titles, for a [query] that doesn't [PopularQuery.loadsAll]: TMDB's [pages]
     * of it, in order. The first batch is [FIRST_PAGES] pages and later ones [MORE_PAGES], so the caller asks
     * for the page after the last it got.
     */
    suspend fun popular(query: PopularQuery, pages: IntRange): PopularBatch = coroutineScope {
        val today = today()
        val fetched = pages.map { page -> async { discover(query, discoverParams(query, null, today, page, paged = true)) } }.awaitAll()
        // A title can slip onto the next page if popularity shifts mid-load.
        val titles = fetched.flatMap { it.results }.distinctBy { it.id }
        // Discover can't say where each movie is in its release, so for the full list that's asked per movie.
        val releases = when {
            query.kind == MediaKind.SHOW -> emptyMap()
            query.streamingOnly -> titles.associate { it.id to Release.Streaming }
            else -> titles.map { t -> async { t.id to runCatching { release(t.id) }.getOrNull() } }.awaitAll().toMap()
        }
        val hasMore = pages.last < minOf(fetched.maxOf { it.totalPages }, MAX_PAGE)
        PopularBatch(titles.map { it.toPopular(query, releases[it.id]) }, hasMore)
    }

    /**
     * Every title matching [query]'s filters, in no particular order, for arranging on the TV. TMDB stops at
     * page 500, so bigger lists are fetched a span of years at a time. [onProgress] gets how many have arrived
     * out of how many there are. Throws [TooManyTitles] past [MAX_ALL] (only movies with few filters get there).
     */
    suspend fun all(query: PopularQuery, onProgress: (loaded: Int, total: Int) -> Unit): List<PopularTitle> = coroutineScope {
        val today = today()
        val gate = Semaphore(PARALLEL)
        var loaded = 0
        var total = 0

        suspend fun page(region: String?, years: IntRange?, page: Int): TmdbPage = gate.withPermit {
            retrying { discover(query, discoverParams(query, region, today, page, paged = false, years)) }
        }

        suspend fun counted(page: TmdbPage) = page.also {
            loaded += it.results.size
            onProgress(minOf(loaded, total), total)
        }

        // Every page of one span of years (or all of them), split in two when it's more than TMDB will page through.
        suspend fun span(region: String?, years: IntRange?, first: TmdbPage): List<TmdbTitle> = coroutineScope {
            val range = years ?: (FIRST_YEAR..Calendar.getInstance().get(Calendar.YEAR) + FUTURE_YEARS)
            if (first.totalPages > MAX_PAGE && range.first < range.last) {
                val middle = (range.first + range.last) / 2
                listOf(range.first..middle, middle + 1..range.last)
                    .map { half -> async { span(region, half, page(region, half, 1)) } }
                    .awaitAll().flatten()
            } else {
                counted(first)
                val rest = (2..minOf(first.totalPages, MAX_PAGE)).map { n -> async { counted(page(region, years, n)) } }
                first.results + rest.awaitAll().flatMap { it.results }
            }
        }

        val regions = query.platform?.regions ?: listOf(null)
        val firsts = regions.map { region -> async { page(region, null, 1) } }.awaitAll()
        if (firsts.any { it.totalResults > MAX_ALL }) throw TooManyTitles(query.kind)
        total = firsts.sumOf { it.totalResults }
        onProgress(0, total)
        val titles = regions.zip(firsts).map { (region, first) -> async { span(region, null, first) } }
            .awaitAll().flatten().distinctBy { it.id }
        // There are too many to ask where each movie is in its release. The add page gets that from Radarr.
        val release = if (query.streamingOnly) Release.Streaming else null
        titles.map { it.toPopular(query, release) }
    }

    private suspend fun discover(query: PopularQuery, params: Map<String, String>) =
        if (query.kind == MediaKind.SHOW) api.discoverShows(params) else api.discoverMovies(params)

    /** Tries again after a network error or TMDB's rate limit, as one failed page out of hundreds would lose the lot. */
    private suspend fun <T> retrying(fetch: suspend () -> T): T {
        repeat(RETRIES) { attempt ->
            try {
                return fetch()
            } catch (e: IOException) {
                delay(1000L shl attempt)
            } catch (e: HttpException) {
                if (e.code() != 429 && e.code() < 500) throw e
                delay(1000L shl attempt)
            }
        }
        return fetch()
    }

    private fun TmdbTitle.toPopular(query: PopularQuery, release: Release?) = PopularTitle(
        kind = query.kind,
        tmdbId = id,
        title = title ?: name ?: "Unknown",
        date = (releaseDate ?: firstAirDate)?.takeIf { it.isNotBlank() },
        rating = voteAverage,
        votes = voteCount,
        popularity = popularity,
        overview = overview.orEmpty(),
        posterPath = posterPath,
        release = release,
    )

    /**
     * The discover filters for one page of [query], in one country when it's limited to a streaming service.
     * [paged] is for the most popular list, sorted and paged by TMDB. Otherwise it's part of a whole list, and
     * [years] limits it to titles that came out (or began) in those years.
     */
    private fun discoverParams(
        query: PopularQuery,
        region: String?,
        today: String,
        page: Int,
        paged: Boolean,
        years: IntRange? = null,
    ): Map<String, String> {
        val from = query.fromYear?.let { "$it-01-01" }
        val movie = query.kind == MediaKind.MOVIE
        // A rating from a handful of votes means nothing, so rating filters need [RATED_VOTES]. Otherwise only a
        // few are asked for: enough to drop near-unknown titles, but not popular ones that few people rate (The
        // Secret Lives of Mormon Wives has 40). Movies not out yet have hardly any, so the most popular movies ask
        // for none. Whole lists always ask for some, or every movie ever filed would be in them.
        val minVotes = when {
            query.minRating != null -> RATED_VOTES
            movie && !query.streamingOnly && paged -> 0
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
                // Shows with an episode aired since then, so a long-running show counts for this year, not the year it began.
                from?.let {
                    put("air_date.gte", it)
                    put("air_date.lte", today)
                }
                query.startedYear?.let { put("first_air_date.gte", "$it-01-01") }
                put("first_air_date.lte", today)
            }
            if (query.platform != null && region != null) {
                put("with_watch_providers", query.platform.providers)
                put("watch_region", region)
                put("with_watch_monetization_types", "flatrate")
            }
            // Narrow the date filters above to the span, keeping whichever bound is tighter.
            if (years != null) {
                val date = if (movie) "primary_release_date" else "first_air_date"
                val from = "${years.first}-01-01"
                val to = "${years.last}-12-31"
                put("$date.gte", maxOf(get("$date.gte") ?: from, from))
                put("$date.lte", minOf(get("$date.lte") ?: to, to))
            }
        }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

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

    companion object {
        /** The first batch is this many pages, and each one after is smaller, as it only has to stay ahead of scrolling. */
        const val FIRST_PAGES = 3
        const val MORE_PAGES = 2

        /** TMDB won't return any page past this. */
        const val MAX_PAGE = 500

        /**
         * The most titles a whole list can have (in each country, for a streaming service). TMDB doesn't count
         * past this. Every show with [MIN_VOTES] is about 19,000, but movies are over 100,000, so they need a filter.
         */
        const val MAX_ALL = 20_000

        /** Pages fetched at once for a whole list, which stays under TMDB's rate limit of about 40 a second. */
        const val PARALLEL = 12

        const val RETRIES = 3

        /** The span of years whole lists are split across when they're too big. TMDB has films from the 1870s. */
        const val FIRST_YEAR = 1870
        const val FUTURE_YEARS = 10

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
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = PARALLEL })
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
