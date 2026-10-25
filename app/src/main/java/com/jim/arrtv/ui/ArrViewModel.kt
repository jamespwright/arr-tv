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
import com.jim.arrtv.data.MediaItem
import com.jim.arrtv.data.MediaKind
import com.jim.arrtv.data.QualityProfile
import com.jim.arrtv.data.QueueItem
import com.jim.arrtv.data.SearchResult
import com.jim.arrtv.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import retrofit2.HttpException

sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object NotConfigured : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Error(val message: String) : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
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
    val status: AddStatus = AddStatus.Ready,
)

/** The combined Radarr + Sonarr queue. [problems] lists services that couldn't be reached. */
data class Queue(val items: List<QueueItem>, val problems: List<String>)

sealed interface RemoveStatus {
    data object Ready : RemoveStatus
    data object Removing : RemoveStatus
    data class Failed(val message: String) : RemoveStatus
}

data class QueueAction(val item: QueueItem, val status: RemoveStatus = RemoveStatus.Ready)

class ArrViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private var repo = ArrRepository(store.load())

    var settings by mutableStateOf(store.load())
        private set
    val library = mutableStateMapOf<MediaKind, Loadable<List<MediaItem>>>()

    var searchKind by mutableStateOf(MediaKind.MOVIE)
        private set
    var searchResults by mutableStateOf<Loadable<List<SearchResult>>>(Loadable.Idle)
        private set
    private var searchJob: Job? = null
    private var lastSearch: String? = null

    /** The search result currently open on the add screen, if any. */
    var adding by mutableStateOf<AddState?>(null)
        private set

    var queue by mutableStateOf<Loadable<Queue>>(Loadable.Loading)
        private set

    /** The download currently open in the queue dialog, if any. */
    var queueAction by mutableStateOf<QueueAction?>(null)
        private set

    init {
        refresh()
    }

    fun saveSettings(newSettings: ArrSettings) {
        store.save(newSettings)
        settings = store.load()
        repo = ArrRepository(settings)
        refresh()
    }

    fun refresh() {
        MediaKind.entries.forEach(::loadLibrary)
    }

    private fun loadLibrary(kind: MediaKind) {
        if (!repo.isConfigured(kind)) {
            library[kind] = Loadable.NotConfigured
            return
        }
        library[kind] = Loadable.Loading
        viewModelScope.launch { library[kind] = load { repo.library(kind) } }
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
        searchJob?.cancel()
        searchResults = Loadable.Idle
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

    fun confirmAdd() {
        val current = adding ?: return
        val profileId = current.selectedProfileId ?: return
        if (current.status == AddStatus.Adding || current.status == AddStatus.Added) return
        updateAdding(current.result) { it.copy(status = AddStatus.Adding) }
        viewModelScope.launch {
            val status = try {
                repo.add(current.result, profileId)
                store.setLastQualityProfile(current.result.kind, profileId)
                AddStatus.Added
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AddStatus.Failed(e.describe())
            }
            updateAdding(current.result) { it.copy(status = status) }
            if (status == AddStatus.Added) loadLibrary(current.result.kind)
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
}
