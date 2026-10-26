package com.jim.arrtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.Color
import com.jim.arrtv.data.Release
import com.jim.arrtv.data.ReleaseStage
import com.jim.arrtv.data.seasonName

@Composable
fun AddScreen(
    state: AddState,
    onSelectProfile: (Int) -> Unit,
    onToggleSeason: (Int) -> Unit,
    onToggleAllSeasons: () -> Unit,
    onAdd: () -> Unit,
    onTrailer: () -> Unit,
    onClose: () -> Unit,
) {
    val result = state.result
    val primaryFocus = remember { FocusRequester() }
    val nothingToDownload = result.seasons.isNotEmpty() && state.selectedSeasons.isEmpty()

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        PosterImage(
            result.posterUrl,
            result.title,
            Modifier.width(320.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // The bottom padding is inside the scrolling area, so the focused button's zoom isn't cut off.
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            Text(result.title, style = MaterialTheme.typography.displaySmall)
            Text(result.subtitle, style = MaterialTheme.typography.titleMedium)
            result.release?.let { ReleaseLine(it) }
            Text(
                result.overview,
                style = MaterialTheme.typography.bodyLarge,
                // Leave room for the season chips.
                maxLines = if (result.seasons.isEmpty()) 7 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.resolving || state.lookupError != null) {
                Text(state.lookupError ?: "Looking it up in ${result.kind.service}…")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.focusRequester(primaryFocus)) {
                        Text(if (state.resolving) "Cancel" else "Back")
                    }
                    TrailerButton(onTrailer)
                }
            } else if (result.inLibrary) {
                Text("Already in ${result.kind.service}.")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.focusRequester(primaryFocus)) { Text("Back") }
                    TrailerButton(onTrailer)
                }
            } else {
                Text("Quality", style = MaterialTheme.typography.titleMedium)
                when (val profiles = state.profiles) {
                    is Loadable.Loaded -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        profiles.value.forEach { profile ->
                            FilterChip(
                                selected = profile.id == state.selectedProfileId,
                                onClick = { onSelectProfile(profile.id) },
                            ) { Text(profile.name) }
                        }
                    }
                    is Loadable.Error -> Text("Couldn't load quality profiles: ${profiles.message}")
                    else -> Text("Loading…")
                }
                if (result.seasons.isNotEmpty()) {
                    SeasonPicker(result.seasons, state.selectedSeasons, onToggleSeason, onToggleAllSeasons)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                    when (state.status) {
                        AddStatus.Added -> Button(onClick = onClose, modifier = Modifier.focusRequester(primaryFocus)) {
                            Text("Done")
                        }
                        else -> {
                            Button(
                                onClick = onAdd,
                                enabled = state.selectedProfileId != null && state.status != AddStatus.Adding,
                                modifier = Modifier.focusRequester(primaryFocus),
                            ) {
                                Text(
                                    when {
                                        state.status == AddStatus.Adding -> "Adding…"
                                        nothingToDownload -> "Add, new seasons only"
                                        else -> "Add and download"
                                    },
                                )
                            }
                            OutlinedButton(onClick = onClose) { Text("Cancel") }
                        }
                    }
                    TrailerButton(onTrailer)
                }
                when (val status = state.status) {
                    AddStatus.Added -> Text(
                        if (nothingToDownload) {
                            "Added to ${result.kind.service}. New seasons will download when they come out."
                        } else {
                            "Added to ${result.kind.service} and searching for downloads. It'll appear in Plex once it's downloaded."
                        },
                    )
                    is AddStatus.Failed -> Text("Couldn't add it: ${status.message}")
                    else -> {}
                }
            }
        }
    }

    // Put focus on the main button whenever the button set changes (opening, after a lookup, or after adding).
    LaunchedEffect(result.key, state.resolving, state.status == AddStatus.Added) {
        runCatching { primaryFocus.requestFocus() }
    }
}

@Composable
fun TrailerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text("▶  Watch trailer") }
}

/** Whether a movie is on streaming yet, with the dates: green once it is, orange in cinemas, grey before. */
@Composable
fun ReleaseLine(release: Release) {
    val color = when (release.stage) {
        ReleaseStage.STREAMING -> Downloaded
        ReleaseStage.IN_CINEMAS -> Color(0xFFFFB74D)
        ReleaseStage.COMING, ReleaseStage.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(release.description, style = MaterialTheme.typography.titleMedium, color = color)
}

/** Chips for choosing which seasons of a show to monitor and download. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonPicker(
    seasons: List<Int>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
) {
    val regular = seasons.filter { it > 0 }
    Text("Seasons to download", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (regular.size > 1) {
            FilterChip(selected = selected.containsAll(regular), onClick = onToggleAll) { Text("All") }
        }
        // Specials go last: they're rarely wanted.
        (regular + seasons.filter { it == 0 }).forEach { number ->
            FilterChip(selected = number in selected, onClick = { onToggle(number) }) { Text(seasonName(number)) }
        }
    }
}
