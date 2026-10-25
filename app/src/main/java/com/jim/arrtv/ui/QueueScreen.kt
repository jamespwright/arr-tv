package com.jim.arrtv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.jim.arrtv.data.MediaKind
import com.jim.arrtv.data.QueueItem
import com.jim.arrtv.data.QueueState
import kotlinx.coroutines.delay

private const val POLL_MS = 5_000L

@Composable
fun QueueScreen(vm: ArrViewModel) {
    // Poll while this tab is showing and the app is in the foreground.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshQueue()
                delay(POLL_MS)
            }
        }
    }

    when (val state = vm.queue) {
        Loadable.Idle, Loadable.Loading -> Message("Loading…")
        Loadable.NotConfigured -> Message("Add your Radarr and Sonarr addresses and API keys in Settings.")
        is Loadable.Error -> Message(state.message)
        is Loadable.Loaded -> {
            val queue = state.value
            Column {
                queue.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                if (queue.items.isEmpty()) {
                    Message("Nothing downloading right now.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(queue.items, key = { it.key }) { item -> QueueRow(item) { vm.openQueueItem(item) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, onClick: () -> Unit) {
    // A dark highlight instead of the default light one, so the coloured status text stays readable.
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
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosterImage(
                item.posterUrl,
                item.title,
                Modifier.width(60.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    listOf(item.title, item.detail).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                QueueProgress(item)
                item.messages.firstOrNull()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Status, progress bar, and size / time left on one line, for the list. */
@Composable
private fun QueueProgress(item: QueueItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.statusLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = item.state.color(),
            modifier = Modifier.width(150.dp),
        )
        QueueBar(item, Modifier.weight(1f))
        Text(item.progressText(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(260.dp))
    }
}

/** The same information stacked, for the narrower detail page. */
@Composable
private fun QueueProgressStacked(item: QueueItem) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(item.statusLabel, style = MaterialTheme.typography.titleMedium, color = item.state.color())
            Text(item.progressText(), style = MaterialTheme.typography.titleMedium)
        }
        QueueBar(item, Modifier.fillMaxWidth())
    }
}

@Composable
private fun QueueBar(item: QueueItem, modifier: Modifier) {
    LinearProgressIndicator(
        progress = { item.progress },
        modifier = modifier.height(6.dp),
        color = item.state.color(),
        trackColor = Color.White.copy(alpha = 0.15f),
    )
}

private fun QueueItem.progressText() = listOfNotNull(sizeLabel.ifBlank { null }, timeLeftLabel).joinToString(" · ")

@Composable
private fun QueueState.color(): Color = when (this) {
    QueueState.PROBLEM -> MaterialTheme.colorScheme.error
    QueueState.DOWNLOADING -> MaterialTheme.colorScheme.primary
    QueueState.IMPORTING -> Color(0xFF81C784)
    QueueState.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Full details for one download, with the remove actions. */
@Composable
fun QueueItemScreen(
    action: QueueAction,
    onRemove: (blocklist: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val item = action.item
    val closeFocus = remember { FocusRequester() }
    val busy = action.status == RemoveStatus.Removing
    val service = item.kind.service
    val what = when {
        item.kind == MediaKind.SHOW && item.ids.size > 1 -> "all ${item.ids.size} episodes in this download"
        else -> "this download"
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        PosterImage(
            item.posterUrl,
            item.title,
            Modifier.width(280.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(item.title, style = MaterialTheme.typography.displaySmall)
            if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.titleMedium)
            QueueProgressStacked(item)
            Text(item.releaseTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.quality, item.source.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.messages.take(4).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)) { Text("Close") }
                OutlinedButton(onClick = { onRemove(true) }, enabled = !busy) { Text("Remove and find another") }
                OutlinedButton(onClick = { onRemove(false) }, enabled = !busy) { Text("Remove") }
            }
            Text(
                "Both remove options delete $what from the download client. " +
                    "\"Find another\" also blocks this release so $service searches for a different one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val status = action.status) {
                RemoveStatus.Removing -> Text("Removing…")
                is RemoveStatus.Failed -> Text("Couldn't remove it: ${status.message}", color = MaterialTheme.colorScheme.error)
                RemoveStatus.Ready -> {}
            }
        }
    }

    // Start on Close so a stray OK press doesn't delete anything.
    LaunchedEffect(item.key) { runCatching { closeFocus.requestFocus() } }
}
