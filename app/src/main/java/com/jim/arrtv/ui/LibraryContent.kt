package com.jim.arrtv.ui

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.jim.arrtv.data.MediaItem
import com.jim.arrtv.data.MediaKind
import com.jim.arrtv.data.Poster

@Composable
fun LibraryContent(state: Loadable<List<MediaItem>>?, kind: MediaKind, onRetry: () -> Unit) {
    LoadableContent(state ?: Loadable.Loading, kind, onRetry, emptyText = "Nothing in ${kind.service} yet.") { items ->
        PosterGrid(items, onClick = {})
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items, key = { it.key }) { item -> PosterCard(item) { onClick(item) } }
    }
}

@Composable
private fun PosterCard(item: Poster, onClick: () -> Unit) {
    Column {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
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
