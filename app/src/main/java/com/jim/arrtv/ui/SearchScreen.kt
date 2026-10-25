package com.jim.arrtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jim.arrtv.data.MediaKind
import com.jim.arrtv.data.PopularTitle
import com.jim.arrtv.data.ReleaseStage
import com.jim.arrtv.data.Poster
import com.jim.arrtv.data.SearchResult
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

/**
 * The search box and filters sit at the top of the poster grid and scroll away with it, so the posters get
 * the whole screen once you move down into them. Before anything is searched, the grid shows popular titles.
 */
@Composable
fun SearchScreen(vm: ArrViewModel) {
    var term by rememberSaveable { mutableStateOf("") }
    var focusResults by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    val submit = {
        keyboard?.hide()
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
    val popular = vm.popular[query] ?: Loadable.Loading
    // Mark what's already in Radarr/Sonarr, so it's easy to spot what's new.
    val markedPopular = remember(popular, library) {
        if (popular !is Loadable.Loaded) return@remember popular
        val owned = (library as? Loadable.Loaded)?.value.orEmpty().mapTo(HashSet()) { it.tmdbId }
        Loadable.Loaded(popular.value.map { it.copy(inLibrary = it.tmdbId in owned) })
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
    ) {
        fullWidth("search") {
            SearchRow(vm, term, onTermChange = { term = it }, onSubmit = submit)
        }
        if (kind == MediaKind.MOVIE) fullWidth("release") { ReleaseFilter(vm) }
        if (showingPopular) fullWidth("filters") { PopularFilters(vm) }
        status(
            state = content,
            onRetry = { if (showingPopular) vm.loadPopular(force = true) else vm.search(term) },
            service = if (showingPopular) "TMDB" else kind.service,
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
        M3Theme(colorScheme = m3DarkColorScheme()) {
            OutlinedTextField(
                value = term,
                onValueChange = onTermChange,
                label = { androidx.compose.material3.Text("Search ${vm.searchKind.label.lowercase()}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.width(560.dp),
            )
        }
    }
}

/**
 * Whether to show only movies already out on streaming (so they'll download), or ones still in cinemas and
 * coming soon too. Every movie poster has a badge saying which it is.
 */
@Composable
private fun ReleaseFilter(vm: ArrViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Show", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(4.dp))
        FilterChip(selected = vm.streamingOnly, onClick = { vm.chooseStreamingOnly(true) }) { Text("On streaming") }
        FilterChip(selected = !vm.streamingOnly, onClick = { vm.chooseStreamingOnly(false) }) { Text("All releases") }
    }
}

/** Year filters for the popular titles: years ago, with null for any year. */
private val popularYears = listOf(null to "Any year", 0 to "This year", 1 to "Last year")

/** Rating filters for the popular titles: the minimum TMDB rating out of 10, with null for any rating. */
private val popularRatings = listOf(null to "Any rating", 7 to "★ 7+", 8 to "★ 8+")

@Composable
private fun PopularFilters(vm: ArrViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Popular ${vm.searchKind.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(4.dp))
        popularYears.forEach { (yearsAgo, label) ->
            FilterChip(selected = vm.popularYearsAgo == yearsAgo, onClick = { vm.choosePopularYear(yearsAgo) }) {
                Text(label)
            }
        }
        Spacer(Modifier.width(20.dp))
        popularRatings.forEach { (rating, label) ->
            FilterChip(selected = vm.popularMinRating == rating, onClick = { vm.choosePopularRating(rating) }) {
                Text(label)
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** A message under the header while the list loads, fails, or comes back empty. Nothing once there are posters. */
private fun LazyGridScope.status(
    state: Loadable<List<Poster>>,
    onRetry: () -> Unit,
    service: String,
    notConfiguredText: String,
    emptyText: String,
) {
    val text = when (state) {
        Loadable.Idle -> return
        Loadable.Loading -> "Loading…"
        Loadable.NotConfigured -> notConfiguredText
        is Loadable.Error -> "Couldn't reach $service: ${state.message}"
        is Loadable.Loaded -> if (state.value.isEmpty()) emptyText else return
    }
    fullWidth("status") {
        Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, textAlign = TextAlign.Center)
                if (state is Loadable.Error) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}
