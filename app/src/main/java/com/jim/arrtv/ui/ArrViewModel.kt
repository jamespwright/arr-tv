package com.jim.arrtv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jim.arrtv.data.ArrRepository
import com.jim.arrtv.data.ArrSettings
import com.jim.arrtv.data.IndexerRelease
import com.jim.arrtv.data.LibraryDetails
import com.jim.arrtv.data.MediaItem
import com.jim.arrtv.data.MediaKind
import com.jim.arrtv.data.PopularQuery
import com.jim.arrtv.data.PopularSort
import com.jim.arrtv.data.PopularTitle
import com.jim.arrtv.data.QualityProfile
import com.jim.arrtv.data.QueueItem
import com.jim.arrtv.data.ReleaseTarget
import com.jim.arrtv.data.SearchResult
import com.jim.arrtv.data.SonarrEpisode
import com.jim.arrtv.data.StreamingPlatform
import com.jim.arrtv.data.code
import com.jim.arrtv.data.seasonName
import com.jim.arrtv.data.SettingsStore
import com.jim.arrtv.data.TmdbRepository
import com.jim.arrtv.data.TooManyTitles
import com.jim.arrtv.data.arrangedBy
import com.jim.arrtv.data.TrailerFinder
import com.jim.arrtv.data.TrailerQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object NotConfigured : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    /** [plain] messages are shown as they are, rather than as the service being unreachable. */
    data class Error(val message: String, val plain: Boolean = false) : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
}

fun <T, R> Loadable<T>.map(transform: (T) -> R): Loadable<R> = when (this) {
    is Loadable.Loaded -> Loadable.Loaded(transform(value))
    is Loadable.Error -> this
    Loadable.Idle -> Loadable.Idle
    Loadable.NotConfigured -> Loadable.NotConfigured
    Loadable.Loading -> Loadable.Loading
}

sealed interface AddStatus {
    data object Ready : AddStatus
    data object Adding : AddStatus
    data object Added : AddStatus
    data class Failed(val message: String) : AddStatus
}

data class AddState(
    val result: SearchResult,
    val profiles: Loadable<List<QualityProfile>> = Loadable.Loading,
    val selectedProfileId: Int? = null,
    /** The seasons to monitor and download, for a show. Starts empty, so nothing downloads unless chosen. */
    val selectedSeasons: Set<Int> = emptySet(),
    val status: AddStatus = AddStatus.Ready,
    /**
     * True while a popular title is being looked up in Radarr/Sonarr. Until then [result] only holds what
     * TMDB knows, which isn't enough to add it. [lookupError] is set if the lookup fails.
     */
    val resolving: Boolean = false,
    val lookupError: String? = null,
)

/** The combined Radarr + Sonarr queue. [problems] lists services that couldn't be reached. */
data class Queue(val items: List<QueueItem>, val problems: List<String>)

sealed interface RemoveStatus {
    data object Ready : RemoveStatus
    data object Removing : RemoveStatus
    data class Failed(val message: String) : RemoveStatus
}

data class QueueAction(val item: QueueItem, val status: RemoveStatus = RemoveStatus.Ready)

/** A short note shown at the bottom of the detail page. [id] makes each one distinct, even with the same text. */
data class DetailMessage(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

/** The library item open on the detail page. [working] is true while a change is being sent. */
data class DetailState(
    val item: MediaItem,
    val details: Loadable<LibraryDetails> = Loadable.Loading,
    val profiles: List<QualityProfile> = emptyList(),
    val working: Boolean = false,
    val message: DetailMessage? = null,
)

/**
 * An interactive search open over the detail page: every release the indexers offered for [title], for picking
 * one by hand. [confirming] is the release waiting on "Download it?", and [grabbing] is true while it's being sent.
 */
data class ReleaseSearch(
    val title: String,
    val target: ReleaseTarget,
    val releases: Loadable<List<IndexerRelease>> = Loadable.Loading,
    val confirming: IndexerRelease? = null,
    val grabbing: Boolean = false,
    val grabError: String? = null,
)

/**
 * The popular titles loaded so far for one set of filters. More are added on the end as the grid nears it, and
 * the ones already there never move, so what's on screen stays put. [nextPage] is the next TMDB page to ask for.
 */
data class PopularList(
    val titles: List<PopularTitle>,
    val nextPage: Int,
    val hasMore: Boolean,
    val loadingMore: Boolean = false,
)

/**
 * The trailer playing over the current page. [videos] are YouTube ids, best first, and [index] is the one
 * playing. The player moves on to the next if YouTube won't play one outside its own app.
 */
data class TrailerState(val title: String, val videos: Loadable<List<String>> = Loadable.Loading, val index: Int = 0) {
    val videoId get() = (videos as? Loadable.Loaded)?.value?.getOrNull(index)
}

class ArrViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private var repo = ArrRepository(store.load())
    private var tmdb = TmdbRepository(store.load().tmdbKey)

    var settings by mutableStateOf(store.load())
        private set
    val library = mutableStateMapOf<MediaKind, Loadable<List<MediaItem>>>()

    /** What's typed into the search box on the Movies and TV Shows tabs. Kept until the app closes. */
    val libraryFilter = mutableStateMapOf<MediaKind, String>()

    var searchKind by mutableStateOf(store.searchKind())
        private set
    var searchResults by mutableStateOf<Loadable<List<SearchResult>>>(Loadable.Idle)
        private set
    private var searchJob: Job? = null
    private var lastSearch: String? = null

    /** The year filter for popular titles on the Search tab: that year or later, or null for any year. */
    var popularFromYear by mutableStateOf(store.popularFromYear())
        private set

    /** For shows, the year they first aired in or after, or null for any year. */
    var popularStartedYear by mutableStateOf(store.popularStartedYear())
        private set

    /** The minimum TMDB rating for popular titles (out of 10), or null for any rating. */
    var popularMinRating by mutableStateOf(store.popularMinRating())
        private set

    /** Whether the Search tab only shows movies already out on streaming, so they'll actually download. */
    var streamingOnly by mutableStateOf(store.streamingOnly())
        private set

    /** How the popular titles are arranged. TMDB sorts them, so changing it fetches a new list. */
    var popularSort by mutableStateOf(store.popularSort())
        private set

    /** The genre filter for popular titles, kept separately for movies and shows since their genres differ. */
    private val popularGenres = mutableStateMapOf<MediaKind, Int?>().apply {
        MediaKind.entries.forEach { put(it, store.popularGenre(it)) }
    }
    val popularGenre get() = popularGenres[searchKind]

    /** The streaming service the popular titles are limited to, or null for any. */
    var popularPlatform by mutableStateOf(store.popularPlatform())
        private set

    /** Most popular lists already fetched, kept until the app closes so flipping between filters is instant. */
    val popular = mutableStateMapOf<PopularQuery, Loadable<PopularList>>()

    /**
     * Whole lists (see [PopularQuery.loadsAll]) under [PopularQuery.allKey], already in every arrangement so
     * switching between them is instant. Kept until the app closes too.
     */
    val popularAll = mutableStateMapOf<PopularQuery, Loadable<Map<PopularSort, List<PopularTitle>>>>()

    /** How far the whole list that's loading has got: titles arrived, and how many there are. */
    val popularAllProgress = mutableStateMapOf<PopularQuery, Pair<Int, Int>>()
    private var allJob: Job? = null
    private var allLoading: PopularQuery? = null

    val popularQuery
        get() = PopularQuery(
            searchKind,
            popularFromYear,
            popularMinRating,
            streamingOnly && searchKind == MediaKind.MOVIE,
            popularGenre,
            popularPlatform,
            popularStartedYear.takeIf { searchKind == MediaKind.SHOW },
            popularSort,
        )

    /** The search result currently open on the add screen, if any. */
    var adding by mutableStateOf<AddState?>(null)
        private set

    var queue by mutableStateOf<Loadable<Queue>>(Loadable.Loading)
        private set

    /** The download currently open in the queue dialog, if any. */
    var queueAction by mutableStateOf<QueueAction?>(null)
        private set

    /** The library item currently open on the detail page, if any. */
    var detail by mutableStateOf<DetailState?>(null)
        private set
    private var detailChanged = false

    /** The interactive search open over the detail page, if any. */
    var releaseSearch by mutableStateOf<ReleaseSearch?>(null)
        private set
    private var releaseJob: Job? = null

    /** The trailer playing, if any. It opens over whichever page it was started from. */
    var trailer by mutableStateOf<TrailerState?>(null)
        private set
    private var trailerJob: Job? = null

    init {
        refresh()
        loadPopular()
    }

    fun saveSettings(newSettings: ArrSettings) {
        store.save(newSettings)
        settings = store.load()
        repo = ArrRepository(settings)
        tmdb = TmdbRepository(settings.tmdbKey)
        popular.clear()
        allJob?.cancel()
        popularAll.clear()
        popularAllProgress.clear()
        refresh()
        loadPopular()
    }

    fun refresh() {
        MediaKind.entries.forEach(::loadLibrary)
    }

    /** With [quiet], the current list stays on screen (keeping its focus and scroll) until the new one arrives. */
    private fun loadLibrary(kind: MediaKind, quiet: Boolean = false) {
        if (!repo.isConfigured(kind)) {
            library[kind] = Loadable.NotConfigured
            return
        }
        val keepCurrent = quiet && library[kind] is Loadable.Loaded
        if (!keepCurrent) library[kind] = Loadable.Loading
        viewModelScope.launch {
            val result = load { repo.library(kind) }
            if (!keepCurrent || result is Loadable.Loaded) library[kind] = result
        }
    }

    fun setLibraryFilter(kind: MediaKind, filter: String) {
        libraryFilter[kind] = filter
    }

    fun openDetails(item: MediaItem) {
        detail = DetailState(item)
        detailChanged = false
        viewModelScope.launch {
            val profiles = async { load { repo.qualityProfiles(item.kind) } }
            val details = load { repo.details(item) }
            val profileList = (profiles.await() as? Loadable.Loaded)?.value.orEmpty()
            updateDetail(item) { it.copy(details = details, profiles = profileList) }
        }
    }

    fun retryDetails() {
        detail?.item?.let(::openDetails)
    }

    fun closeDetails() {
        val item = detail?.item ?: return
        detail = null
        // The grid's subtitles show download and monitoring status, so refresh them if anything changed.
        if (detailChanged) loadLibrary(item.kind, quiet = true)
    }

    fun clearDetailMessage(id: Long) {
        detail?.takeIf { it.message?.id == id }?.let { detail = it.copy(message = null) }
    }

    fun searchDetails() {
        val title = detail?.item?.title ?: return
        detailAction(SEARCHING, "Searching for $title. Anything found will show up in Downloads.") { repo.searchIndexers(it) }
    }

    fun setDetailMonitored(monitored: Boolean) = detailAction { repo.setMonitored(it, monitored) }

    fun setDetailQualityProfile(profileId: Int) = detailAction { repo.setQualityProfile(it, profileId) }

    fun searchSeason(seasonNumber: Int) =
        detailAction(SEARCHING, "Searching for ${seasonName(seasonNumber)}. Anything found will show up in Downloads.") {
            repo.searchSeason(it as LibraryDetails.Show, seasonNumber)
        }

    fun setSeasonMonitored(seasonNumber: Int, monitored: Boolean) =
        detailAction { repo.setSeasonMonitored(it as LibraryDetails.Show, seasonNumber, monitored) }

    fun searchEpisode(episode: SonarrEpisode) =
        detailAction(SEARCHING, "Searching for ${episode.code}. Anything found will show up in Downloads.") {
            repo.searchEpisode(episode)
        }

    fun setEpisodeMonitored(episode: SonarrEpisode, monitored: Boolean) =
        detailAction { repo.setEpisodeMonitored(episode, monitored) }

    fun interactiveSearchMovie() {
        val movie = ((detail?.details as? Loadable.Loaded)?.value as? LibraryDetails.Movie)?.movie ?: return
        openReleaseSearch(movie.title, ReleaseTarget.Movie(movie.id))
    }

    fun interactiveSearchSeason(seasonNumber: Int) {
        val series = ((detail?.details as? Loadable.Loaded)?.value as? LibraryDetails.Show)?.series ?: return
        openReleaseSearch("${series.title} · ${seasonName(seasonNumber)}", ReleaseTarget.Season(series.id, seasonNumber))
    }

    fun interactiveSearchEpisode(episode: SonarrEpisode) {
        val series = ((detail?.details as? Loadable.Loaded)?.value as? LibraryDetails.Show)?.series ?: return
        openReleaseSearch("${series.title} · ${episode.code}", ReleaseTarget.Episode(episode.id))
    }

    private fun openReleaseSearch(title: String, target: ReleaseTarget) {
        releaseJob?.cancel()
        val state = ReleaseSearch(title, target)
        releaseSearch = state
        releaseJob = viewModelScope.launch {
            val releases = load { repo.releases(target) }
            if (releaseSearch === state) releaseSearch = state.copy(releases = releases)
        }
    }

    fun retryReleaseSearch() {
        releaseSearch?.let { openReleaseSearch(it.title, it.target) }
    }

    /** Closing cancels a search still waiting on the indexers. */
    fun closeReleaseSearch() {
        releaseJob?.cancel()
        releaseSearch = null
    }

    fun confirmRelease(release: IndexerRelease?) {
        releaseSearch?.takeIf { !it.grabbing }?.let { releaseSearch = it.copy(confirming = release, grabError = null) }
    }

    /** Grabs the release being confirmed. On success, the search closes and the detail page says so. */
    fun grabRelease() {
        val state = releaseSearch ?: return
        val release = state.confirming ?: return
        if (state.grabbing) return
        releaseSearch = state.copy(grabbing = true, grabError = null)
        viewModelScope.launch {
            try {
                repo.grab(state.target.kind, release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (releaseSearch?.target == state.target) {
                    releaseSearch = releaseSearch?.copy(grabbing = false, grabError = e.describe())
                }
                return@launch
            }
            if (releaseSearch?.target == state.target) closeReleaseSearch()
            detailChanged = true
            detail?.let { detail = it.copy(message = DetailMessage("Downloading ${release.title}. It'll show up in Downloads.")) }
        }
    }

    fun deleteDetails(deleteFiles: Boolean) = detailAction(reload = false) {
        repo.delete(it, deleteFiles)
        val item = detail?.item
        detail = null
        item?.let { loadLibrary(it.kind, quiet = true) }
    }

    /**
     * Sends a change for the open item, then reloads it so the page shows what the server now has.
     * The old details stay on screen meanwhile, so focus doesn't move. [working] is shown while it runs
     * and [done] on success.
     */
    private fun detailAction(
        working: String = "Saving…",
        done: String? = null,
        reload: Boolean = true,
        action: suspend (LibraryDetails) -> Unit,
    ) {
        val state = detail ?: return
        val details = (state.details as? Loadable.Loaded)?.value ?: return
        if (state.working) return
        detail = state.copy(working = true, message = DetailMessage(working))
        viewModelScope.launch {
            val message = try {
                action(details)
                detailChanged = true
                if (reload) {
                    val fresh = load { repo.details(state.item) }
                    updateDetail(state.item) { it.copy(details = fresh as? Loadable.Loaded ?: it.details) }
                }
                done?.let { DetailMessage(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DetailMessage("That didn't work: ${e.describe()}", isError = true)
            }
            updateDetail(state.item) { it.copy(working = false, message = message) }
        }
    }

    /** Only applies the update if the same item is still open (the user may have backed out). */
    private fun updateDetail(item: MediaItem, update: (DetailState) -> DetailState) {
        detail?.takeIf { it.item.key == item.key }?.let { detail = update(it) }
    }

    /**
     * Fetches both queues. The previous list stays on screen while this runs, so polling doesn't flash
     * "Loading…" or lose focus.
     */
    suspend fun refreshQueue() {
        val kinds = MediaKind.entries.filter { repo.isConfigured(it) }
        if (kinds.isEmpty()) {
            queue = Loadable.NotConfigured
            return
        }
        val results = kinds.map { kind -> viewModelScope.async { kind to load { repo.queue(kind) } } }.awaitAll()
        val items = results.flatMap { (_, r) -> (r as? Loadable.Loaded)?.value.orEmpty() }
            .sortedWith(compareBy<QueueItem> { it.state }.thenByDescending { it.progress })
        val problems = results.mapNotNull { (kind, r) -> (r as? Loadable.Error)?.let { "Couldn't reach ${kind.service}: ${it.message}" } }
        queue = if (problems.size == results.size) Loadable.Error(problems.joinToString("\n")) else Loadable.Loaded(Queue(items, problems))
        // Keep the open dialog showing the latest progress.
        queueAction?.let { action ->
            items.firstOrNull { it.key == action.item.key }?.let { queueAction = action.copy(item = it) }
        }
    }

    fun openQueueItem(item: QueueItem) {
        queueAction = QueueAction(item)
    }

    fun closeQueueItem() {
        queueAction = null
    }

    fun removeQueueItem(blocklist: Boolean) {
        val action = queueAction ?: return
        if (action.status == RemoveStatus.Removing) return
        queueAction = action.copy(status = RemoveStatus.Removing)
        viewModelScope.launch {
            try {
                repo.removeFromQueue(action.item, blocklist)
                if (queueAction?.item?.key == action.item.key) queueAction = null
                refreshQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (queueAction?.item?.key == action.item.key) {
                    queueAction = queueAction?.copy(status = RemoveStatus.Failed(e.describe()))
                }
            }
        }
    }

    fun chooseSearchKind(kind: MediaKind) {
        if (kind == searchKind) return
        searchKind = kind
        store.setSearchKind(kind)
        searchJob?.cancel()
        searchResults = Loadable.Idle
        loadPopular()
    }

    fun choosePopularYear(fromYear: Int?) {
        if (fromYear == popularFromYear) return
        popularFromYear = fromYear
        store.setPopularFromYear(fromYear)
        loadPopular()
    }

    fun choosePopularStartedYear(year: Int?) {
        if (year == popularStartedYear) return
        popularStartedYear = year
        store.setPopularStartedYear(year)
        loadPopular()
    }

    fun choosePopularRating(minRating: Int?) {
        if (minRating == popularMinRating) return
        popularMinRating = minRating
        store.setPopularMinRating(minRating)
        loadPopular()
    }

    fun chooseStreamingOnly(only: Boolean) {
        if (only == streamingOnly) return
        streamingOnly = only
        store.setStreamingOnly(only)
        loadPopular()
    }

    fun choosePopularSort(sort: PopularSort) {
        if (sort == popularSort) return
        popularSort = sort
        store.setPopularSort(sort)
        loadPopular()
    }

    fun choosePopularGenre(genre: Int?) {
        if (genre == popularGenre) return
        popularGenres[searchKind] = genre
        store.setPopularGenre(searchKind, genre)
        loadPopular()
    }

    fun choosePopularPlatform(platform: StreamingPlatform?) {
        if (platform == popularPlatform) return
        popularPlatform = platform
        store.setPopularPlatform(platform)
        loadPopular()
    }

    fun openTrailer(query: TrailerQuery) {
        trailerJob?.cancel()
        val state = TrailerState(query.title)
        trailer = state
        val finder = TrailerFinder(tmdb)
        trailerJob = viewModelScope.launch {
            val videos = load { finder.find(query) }
            if (trailer === state) trailer = state.copy(videos = videos)
        }
    }

    /** Tries the next trailer, after YouTube refused to play this one. */
    fun nextTrailer(failedId: String) {
        val current = trailer ?: return
        if (current.videoId == failedId) trailer = current.copy(index = current.index + 1)
    }

    fun closeTrailer() {
        trailerJob?.cancel()
        trailer = null
    }

    /**
     * Fetches the current popular list (its first batch, for most popular), unless it's already loaded (or
     * loading). [force] retries after an error.
     */
    fun loadPopular(force: Boolean = false) {
        val query = popularQuery
        if (query.loadsAll) return loadAllPopular(query.allKey, force)
        if (!tmdb.isConfigured) {
            popular[query] = Loadable.NotConfigured
            return
        }
        val current = popular[query]
        if (!force && (current is Loadable.Loaded || current is Loadable.Loading)) return
        popular[query] = Loadable.Loading
        val source = tmdb
        viewModelScope.launch {
            val result = load { source.popular(query, 1..TmdbRepository.FIRST_PAGES) }
            if (tmdb !== source) return@launch
            popular[query] = when (result) {
                is Loadable.Loaded -> Loadable.Loaded(
                    PopularList(result.value.titles, TmdbRepository.FIRST_PAGES + 1, result.value.hasMore),
                )
                is Loadable.Error -> result
                else -> Loadable.Loading
            }
        }
    }

    private fun loadAllPopular(key: PopularQuery, force: Boolean) {
        if (!tmdb.isConfigured) {
            popularAll[key] = Loadable.NotConfigured
            return
        }
        val current = popularAll[key]
        if (!force && (current is Loadable.Loaded || current is Loadable.Loading)) return
        // Only one whole list loads at a time, as one can be hundreds of pages. A list left half-loaded starts
        // again when it's next shown.
        allJob?.cancel()
        allLoading?.let {
            popularAll.remove(it)
            popularAllProgress.remove(it)
        }
        popularAll[key] = Loadable.Loading
        allLoading = key
        val source = tmdb
        allJob = viewModelScope.launch {
            val result = try {
                val titles = source.all(key) { loaded, total -> popularAllProgress[key] = loaded to total }
                val arranged = withContext(Dispatchers.Default) {
                    PopularSort.entries.associateWith { titles.arrangedBy(it) }
                }
                Loadable.Loaded(arranged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TooManyTitles) {
                Loadable.Error(e.message.orEmpty(), plain = true)
            } catch (e: Exception) {
                Loadable.Error(e.describe())
            }
            if (tmdb !== source) return@launch
            popularAll[key] = result
            popularAllProgress.remove(key)
            allLoading = null
        }
    }

    /**
     * Adds the next batch to the current popular list, when the grid is nearing its end. It goes on the end of
     * the list of the filters it was asked for, whichever are showing by the time it arrives, and titles already
     * there are skipped, so nothing shifts under the cursor.
     */
    fun loadMorePopular() {
        val query = popularQuery
        if (query.loadsAll) return
        val source = tmdb
        val start = (popular[query] as? Loadable.Loaded)?.value ?: return
        if (start.loadingMore || !start.hasMore) return
        popular[query] = Loadable.Loaded(start.copy(loadingMore = true))
        viewModelScope.launch {
            var list = start
            // A batch can be all repeats (a title moving up a page), which would leave the grid with nothing new
            // to scroll to and so never ask again. Go on to the next batch until something is added.
            repeat(MAX_EMPTY_BATCHES) {
                val pages = list.nextPage until list.nextPage + TmdbRepository.MORE_PAGES
                val result = load { source.popular(query, pages) }
                if (tmdb !== source) return@launch
                if (result !is Loadable.Loaded) {
                    popular[query] = Loadable.Loaded(list.copy(loadingMore = false))
                    return@launch
                }
                val known = list.titles.mapTo(HashSet()) { it.tmdbId }
                val added = result.value.titles.filter { known.add(it.tmdbId) }
                list = list.copy(titles = list.titles + added, nextPage = pages.last + 1, hasMore = result.value.hasMore)
                if (added.isNotEmpty() || !list.hasMore) {
                    popular[query] = Loadable.Loaded(list.copy(loadingMore = false))
                    return@launch
                }
            }
            popular[query] = Loadable.Loaded(list.copy(loadingMore = false))
        }
    }

    /**
     * Opens the add page for a popular title. It shows TMDB's details straight away, then fills in the rest
     * once Radarr/Sonarr have looked it up.
     */
    fun openPopular(title: PopularTitle) {
        val placeholder = SearchResult(
            kind = title.kind,
            key = title.key,
            title = title.title,
            subtitle = title.year?.toString().orEmpty(),
            posterUrl = title.largePosterUrl,
            overview = title.overview,
            inLibrary = false,
            seasons = emptyList(),
            raw = JsonObject(emptyMap()),
            trailer = TrailerQuery(title.kind, title.title, title.year, title.tmdbId),
            release = title.release,
        )
        if (!repo.isConfigured(title.kind)) {
            adding = AddState(placeholder, lookupError = "Add your ${title.kind.service} address and API key in Settings.")
            return
        }
        adding = AddState(placeholder, resolving = true)
        loadProfiles(placeholder)
        viewModelScope.launch {
            val found = load { repo.lookup(title) }
            updateAdding(placeholder) {
                when {
                    found is Loadable.Error -> it.copy(resolving = false, lookupError = "Couldn't reach ${title.kind.service}: ${found.message}")
                    found is Loadable.Loaded && found.value != null -> {
                        // The poster's badge came from TMDB's worldwide dates. Keep it if Radarr's differ, so
                        // the page agrees with the badge, but take Radarr's dates when they agree.
                        val release = found.value.release?.takeIf { r -> r.stage == title.release?.stage } ?: title.release
                        // Keep TMDB's poster, title and description too. Sonarr's come from TheTVDB and are often
                        // different, so they'd swap under you a moment after the page opens. (Adding sends Sonarr's own record.)
                        val shown = found.value.copy(
                            title = placeholder.title,
                            overview = placeholder.overview.ifBlank { found.value.overview },
                            posterUrl = placeholder.posterUrl ?: found.value.posterUrl,
                            release = release ?: found.value.release,
                        )
                        it.copy(result = shown, resolving = false)
                    }
                    else -> it.copy(resolving = false, lookupError = "${title.kind.service} couldn't find this one.")
                }
            }
        }
    }

    fun search(term: String) {
        val kind = searchKind
        lastSearch = term
        searchJob?.cancel()
        when {
            term.isBlank() -> searchResults = Loadable.Idle
            !repo.isConfigured(kind) -> searchResults = Loadable.NotConfigured
            else -> {
                searchResults = Loadable.Loading
                searchJob = viewModelScope.launch { searchResults = load { repo.search(kind, term.trim()) } }
            }
        }
    }

    fun openResult(result: SearchResult) {
        adding = AddState(result)
        loadProfiles(result)
    }

    private fun loadProfiles(result: SearchResult) {
        viewModelScope.launch {
            val profiles = load { repo.qualityProfiles(result.kind) }
            val list = (profiles as? Loadable.Loaded)?.value.orEmpty()
            val remembered = store.lastQualityProfile(result.kind)?.takeIf { id -> list.any { it.id == id } }
            val default = remembered
                ?: list.firstOrNull { it.name.contains("1080") }?.id
                ?: list.firstOrNull()?.id
            updateAdding(result) { it.copy(profiles = profiles, selectedProfileId = default) }
        }
    }

    fun selectProfile(id: Int) {
        val current = adding ?: return
        updateAdding(current.result) { it.copy(selectedProfileId = id) }
    }

    fun toggleSeason(seasonNumber: Int) {
        val current = adding ?: return
        val seasons = current.selectedSeasons
        updateAdding(current.result) {
            it.copy(selectedSeasons = if (seasonNumber in seasons) seasons - seasonNumber else seasons + seasonNumber)
        }
    }

    /** Selects every season except specials, or clears them all if they're already selected. */
    fun toggleAllSeasons() {
        val current = adding ?: return
        val all = current.result.seasons.filter { it > 0 }.toSet()
        updateAdding(current.result) { it.copy(selectedSeasons = if (current.selectedSeasons.containsAll(all)) emptySet() else all) }
    }

    fun confirmAdd() {
        val current = adding ?: return
        if (current.resolving || current.lookupError != null) return
        val profileId = current.selectedProfileId ?: return
        if (current.status == AddStatus.Adding || current.status == AddStatus.Added) return
        updateAdding(current.result) { it.copy(status = AddStatus.Adding) }
        viewModelScope.launch {
            val status = try {
                repo.add(current.result, profileId, current.selectedSeasons)
                store.setLastQualityProfile(current.result.kind, profileId)
                AddStatus.Added
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AddStatus.Failed(e.describe())
            }
            updateAdding(current.result) { it.copy(status = status) }
            if (status == AddStatus.Added) loadLibrary(current.result.kind, quiet = true)
        }
    }

    fun closeResult() {
        val added = adding?.status == AddStatus.Added
        adding = null
        // Re-run the search so the result now shows "In library".
        if (added) lastSearch?.let(::search)
    }

    /** Only applies the update if the same result is still open (the user may have backed out). */
    private fun updateAdding(result: SearchResult, update: (AddState) -> AddState) {
        adding?.takeIf { it.result.key == result.key }?.let { adding = update(it) }
    }

    private suspend fun <T> load(fetch: suspend () -> T): Loadable<T> =
        try {
            Loadable.Loaded(fetch())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loadable.Error(e.describe())
        }

    private fun Exception.describe(): String =
        if (this is HttpException) {
            val body = response()?.errorBody()?.string()?.take(300)
            "HTTP ${code()}" + (body?.let { ": $it" } ?: "")
        } else {
            message ?: javaClass.simpleName
        }

    private companion object {
        /** How many batches of nothing but repeats to skip past before giving up for now. */
        const val MAX_EMPTY_BATCHES = 5
        const val SEARCHING = "Starting search…"
    }
}
