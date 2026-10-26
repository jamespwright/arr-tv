package com.jim.arrtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.jim.arrtv.data.IndexerRelease
import com.jim.arrtv.data.formatSize

private val SIZE_WIDTH = 110.dp
private val SEEDERS_WIDTH = 90.dp

/** Interactive search: every release found for one movie, episode or season, to pick one to download. */
@Composable
fun ReleaseScreen(state: ReleaseSearch, vm: ArrViewModel) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(state.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (val releases = state.releases) {
            is Loadable.Loaded -> ReleaseList(releases.value, vm::confirmRelease)
            is Loadable.Error -> SearchFailed(releases.message, vm::retryReleaseSearch, vm::closeReleaseSearch)
            else -> Searching()
        }
    }

    // A dialog of its own, so the list keeps its scroll position and focus when "Download it?" closes.
    state.confirming?.let { release ->
        Dialog(onDismissRequest = { vm.confirmRelease(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ConfirmGrab(release, state.grabbing, state.grabError, onGrab = vm::grabRelease, onCancel = { vm.confirmRelease(null) })
        }
    }
}

@Composable
private fun Searching() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.onSurface)
        Text("Searching indexers…", style = MaterialTheme.typography.titleLarge)
        Text(
            "This can take a minute or two. Press Back to cancel.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchFailed(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("The search didn't work: $message", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
    }
    LaunchedEffect(message) { runCatching { retryFocus.requestFocus() } }
}

@Composable
private fun ReleaseList(releases: List<IndexerRelease>, onSelect: (IndexerRelease) -> Unit) {
    if (releases.isEmpty()) {
        Text("The indexers didn't find anything.", style = MaterialTheme.typography.titleMedium)
        return
    }
    val rejected = releases.count { it.rejected }
    Text(
        buildString {
            append(if (releases.size == 1) "1 release" else "${releases.size} releases")
            if (rejected > 0) append(" · $rejected rejected (marked red, and can still be downloaded)")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ReleaseColumns(
        name = { HeaderText("Name") },
        size = { HeaderText("Size") },
        seeders = { HeaderText("Seeders") },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    val firstFocus = remember { FocusRequester() }
    // The padding leaves room for the focused row's border and zoom at either end.
    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
        items(releases, key = { it.key }) { release ->
            ReleaseRow(release, if (release === releases.first()) Modifier.focusRequester(firstFocus) else Modifier) {
                onSelect(release)
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {} // let the list lay out its first rows
        runCatching { firstFocus.requestFocus() }
    }
}

@Composable
private fun ReleaseRow(release: IndexerRelease, modifier: Modifier, onClick: () -> Unit) {
    ListRow(onClick, modifier) {
        ReleaseColumns(
            name = {
                Column {
                    Text(
                        release.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (release.rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (release.rejections.isNotEmpty()) {
                        Text(
                            release.rejections.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            size = { Text(release.sizeLabel(), style = MaterialTheme.typography.bodyLarge) },
            seeders = { Text(release.seedersLabel(), style = MaterialTheme.typography.bodyLarge) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** The name takes the space left over; size and seeders are right-aligned columns. */
@Composable
private fun ReleaseColumns(
    name: @Composable () -> Unit,
    size: @Composable () -> Unit,
    seeders: @Composable () -> Unit,
    modifier: Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { name() }
        Box(Modifier.width(SIZE_WIDTH), contentAlignment = Alignment.CenterEnd) { size() }
        Box(Modifier.width(SEEDERS_WIDTH), contentAlignment = Alignment.CenterEnd) { seeders() }
    }
}

@Composable
private fun HeaderText(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
}

/** "Download it?" over the list. Focus starts on Cancel, so a stray second OK doesn't start a download. */
@Composable
private fun ConfirmGrab(
    release: IndexerRelease,
    grabbing: Boolean,
    error: String?,
    onGrab: () -> Unit,
    onCancel: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 900.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Download this release?", style = MaterialTheme.typography.headlineSmall)
                Text(release.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${release.sizeLabel()} · ${release.seedersLabel()} seeders",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (release.rejected) {
                    Text("Rejected by your settings:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    release.rejections.forEach {
                        Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!release.downloadAllowed) {
                    Text("This release can't be downloaded.", color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Cancel") }
                    OutlinedButton(onClick = onGrab, enabled = !grabbing && release.downloadAllowed) { Text("Download") }
                }
                when {
                    grabbing -> Text("Sending it to the download client…")
                    error != null -> Text("That didn't work: $error", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    LaunchedEffect(release.key) { runCatching { cancelFocus.requestFocus() } }
}

private fun IndexerRelease.sizeLabel() = if (size > 0) formatSize(size) else "—"

private fun IndexerRelease.seedersLabel() = when {
    seeders != null -> seeders.toString()
    protocol == "usenet" -> "Usenet"
    else -> "—"
}
