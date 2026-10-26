package com.jim.arrtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jim.arrtv.data.MediaKind
import com.jim.arrtv.data.PopularSort
import com.jim.arrtv.data.PopularTitle
import com.jim.arrtv.data.ReleaseStage
import com.jim.arrtv.data.Poster
import com.jim.arrtv.data.SearchResult
import com.jim.arrtv.data.StreamingPlatform
import com.jim.arrtv.data.tmdbGenres
import java.util.Calendar
import java.util.Locale

/**
 * The search box and filters sit at the top of the poster grid and scroll away with it, so the posters get
 * the whole screen once you move down into them. Before anything is searched, the grid shows popular titles.
 */
@Composable
fun SearchScreen(vm: ArrViewModel) {
    var term by rememberSaveable { mutableStateOf("") }
    var focusResults by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    val submit = {
        focusResults = true
        vm.search(term)
    }

    // After a search from the keyboard, jump down to the results so they can be browsed straight away.
    LaunchedEffect(vm.searchResults) {
        if (focusResults && vm.searchResults !is Loadable.Loading) {
            focusResults = false
            if (vm.searchResults is Loadable.Loaded) {
                withFrameNanos {}
                focusManager.moveFocus(FocusDirection.Down)
            }
        }
    }

    val showingPopular = vm.searchResults is Loadable.Idle
    val kind = vm.searchKind
    val query = vm.popularQuery
    val library = vm.library[kind]
    val popular = if (query.loadsAll) {
        (vm.popularAll[query.allKey] ?: Loadable.Loading).map { it.getValue(query.sort) }
    } else {
        (vm.popular[query] ?: Loadable.Loading).map { it.titles }
    }
    // Mark what's already in Radarr/Sonarr, so it's easy to spot what's new.
    val markedPopular = remember(popular, library) {
        val owned = (library as? Loadable.Loaded)?.value.orEmpty().mapTo(HashSet()) { it.tmdbId }
        popular.map { titles -> titles.map { it.copy(inLibrary = it.tmdbId in owned) } }
    }
    // Radarr's search can't filter by release, so the streaming filter is applied here.
    val filterSearch = kind == MediaKind.MOVIE && vm.streamingOnly
    val results = vm.searchResults
    val shownResults = remember(results, filterSearch) {
        if (filterSearch && results is Loadable.Loaded) {
            Loadable.Loaded(results.value.filter { it.release?.stage == ReleaseStage.STREAMING })
        } else {
            results
        }
    }
    val hiddenResults = ((results as? Loadable.Loaded)?.value?.size ?: 0) - ((shownResults as? Loadable.Loaded)?.value?.size ?: 0)
    val content: Loadable<List<Poster>> = if (showingPopular) markedPopular else shownResults

    // A new list starts at the top.
    LaunchedEffect(showingPopular, query) { gridState.scrollToItem(0) }

    // The header stays in the same grid whatever the list's state, so a focused filter chip keeps its focus
    // while the next list loads.
    PosterGrid(
        items = (content as? Loadable.Loaded)?.value.orEmpty(),
        onClick = { poster ->
            when (poster) {
                is PopularTitle -> vm.openPopular(poster)
                is SearchResult -> vm.openResult(poster)
            }
        },
        state = gridState,
        keepFocus = false,
        // Room for the focused search box to grow without its top being cut off.
        contentPadding = PaddingValues(top = 8.dp),
        onNearEnd = { if (showingPopular) vm.loadMorePopular() },
    ) {
        fullWidth("search") {
            SearchRow(vm, term, onTermChange = { term = it }, onSubmit = submit)
        }
        // With popular titles showing, the streaming filter shares their row.
        if (showingPopular) {
            fullWidth("filters") { PopularFilters(vm) }
        } else if (kind == MediaKind.MOVIE) {
            fullWidth("release") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Show", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(4.dp))
                    ReleaseDropdown(vm)
                }
            }
        }
        status(
            state = content,
            onRetry = { if (showingPopular) vm.loadPopular(force = true) else vm.search(term) },
            service = if (showingPopular) "TMDB" else kind.service,
            loadingText = vm.popularAllProgress[query.allKey]?.takeIf { showingPopular && query.loadsAll }?.let { (loaded, total) ->
                "Loading every ${if (kind == MediaKind.MOVIE) "movie" else "show"} to arrange them\u2026\n\n" +
                    "%,d of %,d".format(Locale.US, loaded, total)
            } ?: "Loading\u2026",
            notConfiguredText = if (showingPopular) {
                "Search for something to add to ${kind.service}.\n\n" +
                    "To see popular ${kind.label.lowercase()} here, add a TMDB API key in Settings."
            } else {
                "Add your ${kind.service} address and API key in Settings."
            },
            emptyText = when {
                showingPopular -> "TMDB has nothing that matches these filters."
                hiddenResults > 0 -> "Nothing on streaming yet matches \"$term\".\n\n" +
                    "Choose \"All releases\" to see ${if (hiddenResults == 1) "the one" else "the $hiddenResults"} still in cinemas or coming soon."
                else -> "No matches for \"$term\"."
            },
        )
    }
}

@Composable
private fun SearchRow(vm: ArrViewModel, term: String, onTermChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaKind.entries.forEach { kind ->
            FilterChip(
                selected = vm.searchKind == kind,
                onClick = {
                    vm.chooseSearchKind(kind)
                    vm.search(term)
                },
            ) { Text(kind.label) }
        }
        Spacer(Modifier.width(12.dp))
        val search = remember { SearchBoxState() }
        SearchBox(
            value = term,
            onValueChange = onTermChange,
            placeholder = "Search ${vm.searchKind.label.lowercase()}",
            onSearch = onSubmit,
            state = search,
            onClear = {
                vm.search("") // back to the popular titles
                search.focus()
            },
        )
    }
}

/**
 * Whether to show only movies already out on streaming (so they'll download), or ones still in cinemas and
 * coming soon too.
 */
@Composable
private fun ReleaseDropdown(vm: ArrViewModel) {
    Dropdown(listOf(true to "On streaming", false to "All releases"), vm.streamingOnly, vm::chooseStreamingOnly)
}

/**
 * Year filters for the popular titles: that year or later, from this year back ten, with null for any year.
 * A year chosen long ago that's no longer in the list still shows.
 */
private fun popularYears(selected: Int?): List<Pair<Int?, String>> {
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val years = (thisYear downTo thisYear - 10).toMutableList()
    if (selected != null && selected !in years) years += selected
    return listOf(null to "Any year") + years.map { it to "$it+" }
}

/** Rating filters for the popular titles: the minimum TMDB rating out of 10, with null for any rating. */
private val popularRatings = listOf(null to "Any rating") + (1..9).map { it to "★ $it+" } + (10 to "★ 10")

/** How the popular titles can be arranged. */
private val popularSorts = PopularSort.entries.map { it to it.label }

/** Streaming services to limit the popular titles to, with null for any. */
private val popularPlatforms = listOf(null to "All platforms") + StreamingPlatform.entries.map { it to it.label }

/**
 * Dropdowns rather than a chip per choice, so they all fit on one row. There's no heading, as the Movies and TV
 * Shows chips above already say which this is, and the row only just fits with the longest choices.
 */
@Composable
private fun PopularFilters(vm: ArrViewModel) {
    val kind = vm.searchKind
    val genres = remember(kind) { listOf(null to "All genres") + tmdbGenres(kind) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Dropdown(popularSorts, vm.popularSort, vm::choosePopularSort, prefix = "Arrange by: ")
        Dropdown(genres, vm.popularGenre, vm::choosePopularGenre)
        Dropdown(popularPlatforms, vm.popularPlatform, vm::choosePopularPlatform)
        if (kind == MediaKind.MOVIE) ReleaseDropdown(vm)
        val shows = kind == MediaKind.SHOW
        Dropdown(
            popularYears(vm.popularFromYear), vm.popularFromYear, vm::choosePopularYear,
            prefix = if (shows) "Latest: " else "",
        )
        if (shows) {
            Dropdown(
                popularYears(vm.popularStartedYear), vm.popularStartedYear, vm::choosePopularStartedYear,
                prefix = "Started: ",
            )
        }
        Dropdown(popularRatings, vm.popularMinRating, vm::choosePopularRating)
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** A message under the header while the list loads, fails, or comes back empty. Nothing once there are posters. */
private fun LazyGridScope.status(
    state: Loadable<List<Poster>>,
    onRetry: () -> Unit,
    service: String,
    loadingText: String,
    notConfiguredText: String,
    emptyText: String,
) {
    val text = when (state) {
        Loadable.Idle -> return
        Loadable.Loading -> loadingText
        Loadable.NotConfigured -> notConfiguredText
        is Loadable.Error -> if (state.plain) state.message else "Couldn't reach $service: ${state.message}"
        is Loadable.Loaded -> if (state.value.isEmpty()) emptyText else return
    }
    fullWidth("status") {
        Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, textAlign = TextAlign.Center)
                if (state is Loadable.Error && !state.plain) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}
