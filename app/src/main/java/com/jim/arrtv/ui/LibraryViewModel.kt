package com.jim.arrtv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jim.arrtv.data.ArrClient
import com.jim.arrtv.data.ArrSettings
import com.jim.arrtv.data.RadarrApi
import com.jim.arrtv.data.SettingsStore
import com.jim.arrtv.data.SonarrApi
import com.jim.arrtv.data.posterUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class MediaItem(val id: Int, val title: String, val subtitle: String, val posterUrl: String?)

sealed interface LibraryState {
    data object NotConfigured : LibraryState
    data object Loading : LibraryState
    data class Error(val message: String) : LibraryState
    data class Loaded(val items: List<MediaItem>) : LibraryState
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    var settings by mutableStateOf(store.load())
        private set
    var movies by mutableStateOf<LibraryState>(LibraryState.Loading)
        private set
    var shows by mutableStateOf<LibraryState>(LibraryState.Loading)
        private set

    init {
        refresh()
    }

    fun saveSettings(newSettings: ArrSettings) {
        store.save(newSettings)
        settings = store.load()
        refresh()
    }

    fun refresh() {
        loadMovies()
        loadShows()
    }

    private fun loadMovies() {
        val s = settings
        if (!s.radarrConfigured) {
            movies = LibraryState.NotConfigured
            return
        }
        movies = LibraryState.Loading
        viewModelScope.launch {
            movies = load {
                ArrClient.create(s.radarrUrl, s.radarrKey, RadarrApi::class.java).movies()
                    .sortedBy { (it.sortTitle ?: it.title).lowercase() }
                    .map { m ->
                        val status = when {
                            m.hasFile -> "Downloaded"
                            m.monitored -> "Wanted"
                            else -> "Not monitored"
                        }
                        MediaItem(m.id, m.title, "${m.year} · $status", m.images.posterUrl())
                    }
            }
        }
    }

    private fun loadShows() {
        val s = settings
        if (!s.sonarrConfigured) {
            shows = LibraryState.NotConfigured
            return
        }
        shows = LibraryState.Loading
        viewModelScope.launch {
            shows = load {
                ArrClient.create(s.sonarrUrl, s.sonarrKey, SonarrApi::class.java).series()
                    .sortedBy { (it.sortTitle ?: it.title).lowercase() }
                    .map { show ->
                        val stats = show.statistics
                        val episodes = stats?.let { "${it.episodeFileCount}/${it.episodeCount} episodes" } ?: ""
                        MediaItem(show.id, show.title, "${show.year} · $episodes", show.images.posterUrl())
                    }
            }
        }
    }

    private suspend fun load(fetch: suspend () -> List<MediaItem>): LibraryState =
        try {
            LibraryState.Loaded(fetch())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LibraryState.Error(e.message ?: e.javaClass.simpleName)
        }
}
