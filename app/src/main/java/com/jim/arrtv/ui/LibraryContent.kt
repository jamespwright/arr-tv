package com.jim.arrtv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.jim.arrtv.data.MediaItem
import com.jim.arrtv.data.MediaKind
import com.jim.arrtv.data.Poster

/** The colour for things that are downloaded and done. */
val Downloaded = Color(0xFF81C784)

@Composable
fun LibraryContent(
    state: Loadable<List<MediaItem>>?,
    kind: MediaKind,
    onRetry: () -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    LoadableContent(state ?: Loadable.Loading, kind, onRetry, emptyText = "Nothing in ${kind.service} yet.") { items ->
        PosterGrid(items, onClick = onOpen)
    }
}

/** Shared loading / not-configured / error handling for anything fetched from Radarr or Sonarr. */
@Composable
fun <T> LoadableContent(
    state: Loadable<List<T>>,
    kind: MediaKind,
    onRetry: () -> Unit,
    emptyText: String,
    idleText: String = "",
    content: @Composable (List<T>) -> Unit,
) {
    when (state) {
        Loadable.Idle -> Message(idleText)
        Loadable.Loading -> Message("Loading…")
        Loadable.NotConfigured -> Message("Add your ${kind.service} address and API key in Settings.")
        is Loadable.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't reach ${kind.service}: ${state.message}", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
        is Loadable.Loaded -> if (state.value.isEmpty()) Message(emptyText) else content(state.value)
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
fun <T : Poster> PosterGrid(items: List<T>, onClick: (T) -> Unit) {
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var focusedIndex by remember { mutableIntStateOf(0) }

    // If the focused poster disappears (it was just deleted), focus would otherwise drop out of the grid
    // and the next key press would land on the tab bar. Move it to whatever now sits in that spot.
    LaunchedEffect(items) {
        val key = focusedKey ?: return@LaunchedEffect
        if (items.isEmpty() || items.any { it.key == key }) return@LaunchedEffect
        val next = items[focusedIndex.coerceAtMost(items.lastIndex)]
        runCatching { focusRequesters[next.key]?.requestFocus() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            val requester = focusRequesters.getOrPut(item.key) { FocusRequester() }
            PosterCard(
                item,
                Modifier.focusRequester(requester).onFocusChanged {
                    if (it.isFocused) {
                        focusedKey = item.key
                        focusedIndex = index
                    }
                },
            ) { onClick(item) }
        }
    }
}

@Composable
private fun PosterCard(item: Poster, modifier: Modifier, onClick: () -> Unit) {
    Column {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            PosterImage(item.posterUrl, item.title)
        }
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(item.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
fun PosterImage(url: String?, title: String, modifier: Modifier = Modifier.fillMaxSize()) {
    AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = modifier)
}

/** A full-width clickable row for lists (downloads, episodes). */
@Composable
fun ListRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    // A dark highlight instead of the default light one, so coloured status text stays readable.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.12f),
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) { content() }
}
