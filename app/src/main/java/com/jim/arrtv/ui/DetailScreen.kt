package com.jim.arrtv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.jim.arrtv.data.LibraryDetails
import com.jim.arrtv.data.MediaFile
import com.jim.arrtv.data.QualityProfile
import com.jim.arrtv.data.SeasonInfo
import com.jim.arrtv.data.SonarrEpisode
import com.jim.arrtv.data.formatDate
import com.jim.arrtv.data.formatRuntime
import com.jim.arrtv.data.formatSize
import com.jim.arrtv.data.parseUtc
import com.jim.arrtv.data.seasonName
import kotlinx.coroutines.delay
import java.util.Date

private const val MESSAGE_MS = 6_000L

/** The page for one movie or show in the library: its status, and searching, monitoring, quality and delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(state: DetailState, vm: ArrViewModel) {
    // On TVs, Compose scrolls the focused item a third of the way down the screen, which pushes the title
    // off the top as soon as the page opens. Scroll only as far as needed to show it instead.
    val margin = with(LocalDensity.current) { 24.dp.toPx() }
    val scrollSpec = remember(margin) { MinimalScroll(margin) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) { DetailPage(state, vm) }
}

@OptIn(ExperimentalFoundationApi::class)
private class MinimalScroll(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0 && end <= containerSize -> 0f
            offset < 0 || size > containerSize -> offset - margin
            else -> end - containerSize + margin
        }
    }
}

@Composable
private fun DetailPage(state: DetailState, vm: ArrViewModel) {
    Box(Modifier.fillMaxSize()) {
        val show = (state.details as? Loadable.Loaded)?.value as? LibraryDetails.Show
        if (show != null) {
            ShowContent(state, show, vm)
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                PosterImage(
                    state.item.posterUrl,
                    state.item.title,
                    Modifier.width(240.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                )
                Box(Modifier.weight(1f)) {
                    when (val details = state.details) {
                        is Loadable.Loaded -> (details.value as? LibraryDetails.Movie)?.let { MovieContent(state, it, vm) }
                        is Loadable.Error -> LoadFailed(state, details.message, vm::retryDetails)
                        else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(state.item.title, style = MaterialTheme.typography.displaySmall)
                            Text("Loading…")
                        }
                    }
                }
            }
        }
        state.message?.let { message ->
            Text(
                message.text,
                color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp)
                    .widthIn(max = 640.dp)
                    .background(Color(0xF0303030), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            // "Saving…" stays until the result replaces it; results fade out on their own.
            LaunchedEffect(message.id, state.working) {
                if (!state.working) {
                    delay(MESSAGE_MS)
                    vm.clearDetailMessage(message.id)
                }
            }
        }
    }
}

@Composable
private fun LoadFailed(state: DetailState, message: String, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(state.item.title, style = MaterialTheme.typography.displaySmall)
        Text("Couldn't load it from ${state.item.kind.service}: $message")
        Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
    }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
}

@Composable
private fun MovieContent(state: DetailState, details: LibraryDetails.Movie, vm: ArrViewModel) {
    val movie = details.movie
    val (status, color) = when {
        movie.hasFile -> listOfNotNull("Downloaded", movie.movieFile?.describe()).joinToString(" · ") to Downloaded
        !movie.isAvailable -> "Not released yet" to MaterialTheme.colorScheme.onSurfaceVariant
        movie.monitored -> "Missing" to MaterialTheme.colorScheme.error
        else -> "Missing" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // The bottom padding is inside the scrolling area, so the focused chip's zoom isn't cut off.
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(movie.title, style = MaterialTheme.typography.displaySmall)
        MetaLine(
            movie.year.takeIf { it > 0 }?.toString(),
            formatRuntime(movie.runtime),
            movie.certification,
            movie.genres.take(3).joinToString(", "),
        )
        movie.overview?.let { Overview(it, maxLines = 5) }
        StatusLine(status, color, movie.monitored)
        if (!movie.hasFile) ReleaseLine(movie.release)
        ItemActions(
            service = state.item.kind.service,
            searchLabel = if (movie.hasFile) "Search again" else "Search now",
            monitored = movie.monitored,
            onSearch = vm::searchDetails,
            onTrailer = { vm.openTrailer(details.trailer) },
            onMonitor = vm::setDetailMonitored,
            onDelete = vm::deleteDetails,
        )
        QualityChips(state.profiles, movie.qualityProfileId, vm::setDetailQualityProfile)
    }
}

@Composable
private fun ShowContent(state: DetailState, details: LibraryDetails.Show, vm: ArrViewModel) {
    val series = details.series
    // Specials go last, and only if Sonarr knows of any.
    val seasons = series.seasons
        .filter { it.seasonNumber > 0 || (it.statistics?.totalEpisodeCount ?: 0) > 0 }
        .sortedBy { if (it.seasonNumber == 0) Int.MAX_VALUE else it.seasonNumber }
    var selectedSeason by remember(state.item.key) {
        mutableIntStateOf(seasons.lastOrNull { it.seasonNumber > 0 }?.seasonNumber ?: seasons.firstOrNull()?.seasonNumber ?: 1)
    }
    var expandedEpisode by remember { mutableStateOf<Int?>(null) }
    val season = seasons.firstOrNull { it.seasonNumber == selectedSeason }
    val episodes = details.episodes.filter { it.seasonNumber == selectedSeason }.sortedBy { it.episodeNumber }
    val now = remember { Date() }
    val stats = series.statistics

    // Two panes, so the episodes get the full height of the screen: the show and its actions on the left,
    // and seasons and episodes on the right (the episode list scrolls on its own).
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PosterImage(
                    state.item.posterUrl,
                    series.title,
                    Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        series.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaLine(
                        series.year.takeIf { it > 0 }?.toString(),
                        series.network,
                        series.status?.replaceFirstChar { it.uppercase() },
                        series.certification,
                    )
                }
            }
            StatusLine(
            listOfNotNull(
                stats?.let { "${it.episodeFileCount} of ${it.episodeCount} episodes" },
                stats?.sizeOnDisk?.takeIf { it > 0 }?.let(::formatSize),
            ).joinToString(" · "),
            if (stats != null && stats.episodeFileCount >= stats.episodeCount) Downloaded else MaterialTheme.colorScheme.onSurface,
                series.monitored,
            )
            series.overview?.let { Overview(it, maxLines = 4) }
            ItemActions(
                service = state.item.kind.service,
                searchLabel = "Search for missing episodes",
                monitored = series.monitored,
                onSearch = vm::searchDetails,
                onTrailer = { vm.openTrailer(details.trailer) },
                onMonitor = vm::setDetailMonitored,
                onDelete = vm::deleteDetails,
                vertical = true,
            )
            QualityChips(state.profiles, series.qualityProfileId, vm::setDetailQualityProfile)
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Starts scrolled to the selected season, which is usually the latest and off the right edge.
            val chipsState = rememberLazyListState(
                initialFirstVisibleItemIndex = seasons.indexOfFirst { it.seasonNumber == selectedSeason }.coerceAtLeast(0),
            )
            LazyRow(state = chipsState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(seasons, key = { it.seasonNumber }) { s ->
                    FilterChip(
                        selected = s.seasonNumber == selectedSeason,
                        onClick = {
                            selectedSeason = s.seasonNumber
                            expandedEpisode = null
                        },
                    ) { Text(seasonName(s.seasonNumber)) }
                }
            }
            season?.let { SeasonActions(it, vm) }
            // A new scroll position per season, so switching seasons starts at the top of the list.
            key(selectedSeason) {
                // The padding leaves room for the focused row's border and zoom at either end.
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    episodes.forEach { episode ->
                        key(episode.id) {
                            EpisodeRow(episode, now) { expandedEpisode = if (expandedEpisode == episode.id) null else episode.id }
                            if (expandedEpisode == episode.id) EpisodeActions(episode, vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaLine(vararg parts: String?) {
    Text(
        parts.filterNot { it.isNullOrBlank() }.joinToString(" · "),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Overview(text: String, maxLines: Int) {
    Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StatusLine(status: String, color: Color, monitored: Boolean) {
    // One Text rather than a Row, so it wraps cleanly in the narrow pane on show pages.
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(status) }
            withStyle(SpanStyle(color = dim)) { append(if (monitored) " · Monitored" else " · Not monitored") }
        },
        style = MaterialTheme.typography.titleMedium,
    )
}

/** Search, trailer, monitor and delete, with delete asking first. Focus starts on search. [vertical] stacks the buttons. */
@Composable
private fun ItemActions(
    service: String,
    searchLabel: String,
    monitored: Boolean,
    onSearch: () -> Unit,
    onTrailer: () -> Unit,
    onMonitor: (Boolean) -> Unit,
    onDelete: (deleteFiles: Boolean) -> Unit,
    vertical: Boolean = false,
) {
    val wide = if (vertical) Modifier.fillMaxWidth() else Modifier
    var confirming by remember { mutableStateOf(false) }
    var askedBefore by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val deleteFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }

    if (!confirming) {
        ButtonGroup(vertical) {
            Button(onClick = onSearch, modifier = wide.focusRequester(searchFocus)) { Text(searchLabel) }
            TrailerButton(onTrailer, wide)
            OutlinedButton(onClick = { onMonitor(!monitored) }, modifier = wide) {
                Text(if (monitored) "Stop monitoring" else "Start monitoring")
            }
            OutlinedButton(
                onClick = {
                    confirming = true
                    askedBefore = true
                },
                modifier = wide.focusRequester(deleteFocus),
            ) { Text("Delete…") }
        }
    } else {
        ButtonGroup(vertical) {
            Button(onClick = { confirming = false }, modifier = wide.focusRequester(cancelFocus)) { Text("Cancel") }
            OutlinedButton(onClick = { onDelete(false) }, modifier = wide) { Text("Remove from $service") }
            OutlinedButton(onClick = { onDelete(true) }, modifier = wide) { Text("Remove and delete files") }
        }
        Text(
            "\"Remove from $service\" keeps the files, so it stays in Plex but won't be upgraded or tracked. " +
                "\"Delete files\" removes it from Plex too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Start on search; after a cancelled delete, go back to the delete button.
    LaunchedEffect(confirming) {
        runCatching {
            when {
                confirming -> cancelFocus.requestFocus()
                askedBefore -> deleteFocus.requestFocus()
                else -> searchFocus.requestFocus()
            }
        }
    }
}

@Composable
private fun ButtonGroup(vertical: Boolean, content: @Composable () -> Unit) {
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** Wraps onto more lines when there isn't room, as in the narrow pane on show pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityChips(profiles: List<QualityProfile>, selectedId: Int, onSelect: (Int) -> Unit) {
    if (profiles.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Quality",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp),
        )
        profiles.forEach { profile ->
            FilterChip(
                selected = profile.id == selectedId,
                onClick = { if (profile.id != selectedId) onSelect(profile.id) },
            ) { Text(profile.name) }
        }
    }
}

@Composable
private fun SeasonActions(season: SeasonInfo, vm: ArrViewModel) {
    val stats = season.statistics
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { vm.searchSeason(season.seasonNumber) }) {
            Text("Search ${seasonName(season.seasonNumber)}")
        }
        OutlinedButton(onClick = { vm.setSeasonMonitored(season.seasonNumber, !season.monitored) }) {
            Text(if (season.monitored) "Stop monitoring season" else "Monitor season")
        }
        Text(
            listOfNotNull(
                stats?.let { "${it.episodeFileCount} of ${it.totalEpisodeCount} downloaded" },
                stats?.sizeOnDisk?.takeIf { it > 0 }?.let(::formatSize),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EpisodeRow(episode: SonarrEpisode, now: Date, onClick: () -> Unit) {
    val airDate = parseUtc(episode.airDateUtc)
    val (status, color) = when {
        episode.hasFile -> (episode.episodeFile?.describe() ?: "Downloaded") to Downloaded
        airDate == null -> "Not announced" to MaterialTheme.colorScheme.onSurfaceVariant
        airDate.after(now) -> "Airs ${formatDate(airDate)}" to MaterialTheme.colorScheme.onSurfaceVariant
        !episode.monitored -> "Not monitored" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Missing" to MaterialTheme.colorScheme.error
    }
    ListRow(onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                episode.episodeNumber.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title ?: "TBA",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                airDate?.let {
                    Text(
                        formatDate(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun EpisodeActions(episode: SonarrEpisode, vm: ArrViewModel) {
    // Expanding the last row on screen would otherwise open it below the bottom edge.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { bringIntoView.bringIntoView() }
    Column(
        Modifier.bringIntoViewRequester(bringIntoView).padding(start = 68.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        episode.overview?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { vm.searchEpisode(episode) }) {
                Text(if (episode.hasFile) "Search again" else "Search for this episode")
            }
            OutlinedButton(onClick = { vm.setEpisodeMonitored(episode, !episode.monitored) }) {
                Text(if (episode.monitored) "Stop monitoring" else "Monitor episode")
            }
        }
    }
}

private fun MediaFile.describe() =
    listOfNotNull(quality?.quality?.name, size.takeIf { it > 0 }?.let(::formatSize)).joinToString(" · ")
