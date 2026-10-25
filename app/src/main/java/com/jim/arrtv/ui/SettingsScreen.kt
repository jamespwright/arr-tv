package com.jim.arrtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jim.arrtv.data.ArrSettings
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

@Composable
fun SettingsScreen(settings: ArrSettings, onSave: (ArrSettings) -> Unit) {
    var radarrUrl by remember(settings) { mutableStateOf(settings.radarrUrl) }
    var radarrKey by remember(settings) { mutableStateOf(settings.radarrKey) }
    var sonarrUrl by remember(settings) { mutableStateOf(settings.sonarrUrl) }
    var sonarrKey by remember(settings) { mutableStateOf(settings.sonarrKey) }
    var tmdbKey by remember(settings) { mutableStateOf(settings.tmdbKey) }

    // Text fields come from regular Material 3 (TV Material has none), so give them a dark theme too.
    M3Theme(colorScheme = m3DarkColorScheme()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Radarr", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("Address (e.g. http://192.168.0.10:7878)", radarrUrl) { radarrUrl = it }
                Field("API key", radarrKey) { radarrKey = it }
            }
            Text("Sonarr", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("Address (e.g. http://192.168.0.10:8989)", sonarrUrl) { sonarrUrl = it }
                Field("API key", sonarrKey) { sonarrKey = it }
            }
            Text("TMDB (popular titles on the Search tab)", style = MaterialTheme.typography.titleLarge)
            Field("API key or read access token", tmdbKey) { tmdbKey = it }
            Button(onClick = { onSave(ArrSettings(radarrUrl, radarrKey, sonarrUrl, sonarrKey, tmdbKey)) }) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        modifier = Modifier.width(420.dp),
    )
}
