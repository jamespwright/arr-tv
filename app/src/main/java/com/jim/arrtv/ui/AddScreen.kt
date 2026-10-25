package com.jim.arrtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun AddScreen(
    state: AddState,
    onSelectProfile: (Int) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    val result = state.result
    val primaryFocus = remember { FocusRequester() }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        PosterImage(
            result.posterUrl,
            result.title,
            Modifier.width(320.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(result.title, style = MaterialTheme.typography.displaySmall)
            Text(result.subtitle, style = MaterialTheme.typography.titleMedium)
            Text(
                result.overview,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 7,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            if (result.inLibrary) {
                Text("Already in ${result.kind.service}.")
                OutlinedButton(onClick = onClose, modifier = Modifier.focusRequester(primaryFocus)) { Text("Back") }
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
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (state.status) {
                        AddStatus.Added -> Button(onClick = onClose, modifier = Modifier.focusRequester(primaryFocus)) {
                            Text("Done")
                        }
                        else -> {
                            Button(
                                onClick = onAdd,
                                enabled = state.selectedProfileId != null && state.status != AddStatus.Adding,
                                modifier = Modifier.focusRequester(primaryFocus),
                            ) { Text(if (state.status == AddStatus.Adding) "Adding…" else "Add and download") }
                            OutlinedButton(onClick = onClose) { Text("Cancel") }
                        }
                    }
                }
                when (val status = state.status) {
                    AddStatus.Added -> Text(
                        "Added to ${result.kind.service} and searching for downloads. It'll appear in Plex once it's downloaded.",
                    )
                    is AddStatus.Failed -> Text("Couldn't add it: ${status.message}")
                    else -> {}
                }
            }
        }
    }

    // Put focus on the main button whenever the button set changes (opening, or after adding).
    LaunchedEffect(result.key, state.status == AddStatus.Added) {
        runCatching { primaryFocus.requestFocus() }
    }
}
