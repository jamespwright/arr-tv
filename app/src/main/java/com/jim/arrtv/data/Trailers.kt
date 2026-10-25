package com.jim.arrtv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** What's needed to find a trailer. [tmdbId] is 0 if unknown. [youTubeId] is Radarr's trailer, for movies. */
data class TrailerQuery(
    val kind: MediaKind,
    val title: String,
    val year: Int?,
    val tmdbId: Int = 0,
    val youTubeId: String? = null,
)

/**
 * Finds YouTube trailers for a title, best first. Some studios block their trailers from playing outside
 * YouTube, so the player works down the list until one plays. TMDB's official trailers come first, then
 * Radarr's, then a plain YouTube search for "[title] trailer".
 */
class TrailerFinder(private val tmdb: TmdbRepository) {
    suspend fun find(query: TrailerQuery): List<String> {
        val fromTmdb = if (tmdb.isConfigured && query.tmdbId > 0) {
            runCatching { tmdb.trailers(query.kind, query.tmdbId) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val searchTerm = listOfNotNull(query.title, query.year?.takeIf { it > 0 }?.toString(), "trailer").joinToString(" ")
        // The search is only a backup, so a failure there only matters if nothing else turned up.
        val fromSearch = runCatching { searchYouTube(searchTerm) }
            .getOrElse { if (fromTmdb.isEmpty() && query.youTubeId == null) throw it else emptyList() }
        return (fromTmdb + listOfNotNull(query.youTubeId?.takeIf { it.isNotBlank() }) + fromSearch).distinct()
    }

    /** The first few videos on YouTube's search results page. YouTube's API would need its own key. */
    private suspend fun searchYouTube(term: String): List<String> = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/results".toHttpUrl().newBuilder().addQueryParameter("search_query", term).build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            .header("Accept-Language", "en")
            .build()
        val page = http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YouTube said HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        VIDEO_ID.findAll(page).map { it.groupValues[1] }.distinct().take(SEARCH_RESULTS).toList()
    }

    private companion object {
        val http = OkHttpClient()

        /** Only real search results, not ads or shorts, which use other renderers. */
        val VIDEO_ID = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")

        const val SEARCH_RESULTS = 5
    }
}
