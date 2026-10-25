package com.jim.arrtv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text

private val tabs = listOf("Movies", "TV Shows", "Settings")
private const val SETTINGS_TAB = 2

@Composable
fun HomeScreen(vm: LibraryViewModel) {
    val firstRun = !vm.settings.radarrConfigured && !vm.settings.sonarrConfigured
    var selected by rememberSaveable { mutableIntStateOf(if (firstRun) SETTINGS_TAB else 0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        TabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = index == selected, onFocus = { selected = index }) {
                    Text(title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        when (selected) {
            0 -> LibraryContent(vm.movies, "Radarr", vm::refresh)
            1 -> LibraryContent(vm.shows, "Sonarr", vm::refresh)
            SETTINGS_TAB -> SettingsScreen(vm.settings, vm::saveSettings)
        }
    }
}
