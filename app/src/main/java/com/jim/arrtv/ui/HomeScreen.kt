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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.jim.arrtv.data.MediaKind

private val tabs = listOf("Search", "Downloads", "Movies", "TV Shows", "Settings")
private const val SETTINGS_TAB = 4

@Composable
fun HomeScreen(vm: ArrViewModel) {
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
            0 -> SearchScreen(vm)
            1 -> QueueScreen(vm)
            2 -> LibraryContent(vm.library[MediaKind.MOVIE], MediaKind.MOVIE, vm::refresh, vm::openDetails)
            3 -> LibraryContent(vm.library[MediaKind.SHOW], MediaKind.SHOW, vm::refresh, vm::openDetails)
            SETTINGS_TAB -> SettingsScreen(vm.settings, vm::saveSettings)
        }
    }

    // A dialog has its own window, so the screen underneath keeps its scroll position and focus for when it closes.
    vm.adding?.let { state ->
        Dialog(onDismissRequest = vm::closeResult, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                AddScreen(
                    state = state,
                    onSelectProfile = vm::selectProfile,
                    onAdd = vm::confirmAdd,
                    onClose = vm::closeResult,
                )
            }
        }
    }

    vm.queueAction?.let { action ->
        Dialog(onDismissRequest = vm::closeQueueItem, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                QueueItemScreen(action = action, onRemove = vm::removeQueueItem, onClose = vm::closeQueueItem)
            }
        }
    }

    vm.detail?.let { state ->
        Dialog(onDismissRequest = vm::closeDetails, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { DetailScreen(state, vm) }
        }
    }
}
