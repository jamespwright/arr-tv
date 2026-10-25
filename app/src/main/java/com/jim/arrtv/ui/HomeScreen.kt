package com.jim.arrtv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.jim.arrtv.data.MediaKind
import kotlinx.coroutines.delay

private val tabs = listOf("Search", "Downloads", "Movies", "TV Shows", "Settings")
private const val SETTINGS_TAB = 4

/** How long the highlight has to rest on a tab before its screen is drawn. */
private const val TAB_SETTLE_MS = 250L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(vm: ArrViewModel) {
    val firstRun = !vm.settings.radarrConfigured && !vm.settings.sonarrConfigured
    var selected by rememberSaveable { mutableIntStateOf(if (firstRun) SETTINGS_TAB else 0) }

    // Drawing a poster grid takes a noticeable moment, so moving across the tabs only moves the highlight.
    // The screen underneath catches up once the highlight rests, or straight away when you press down.
    var shown by rememberSaveable { mutableIntStateOf(selected) }
    var moveDownWhenShown by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(selected) {
        if (shown != selected) {
            delay(TAB_SETTLE_MS)
            shown = selected
        }
    }
    LaunchedEffect(shown, moveDownWhenShown) {
        if (moveDownWhenShown) {
            withFrameNanos {} // let the new screen lay out first
            focusManager.moveFocus(FocusDirection.Down)
            moveDownWhenShown = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        val tabFocus = remember { tabs.map { FocusRequester() } }
        // Coming up from a screen, land on the tab you're on rather than whichever tab is nearest.
        TabRow(
            selectedTabIndex = selected,
            modifier = Modifier
                .focusProperties { enter = { tabFocus[selected] } }
                .focusGroup()
                .onPreviewKeyEvent {
                    // Pressing down before the screen has caught up would move focus into the old one.
                    if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown && shown != selected) {
                        shown = selected
                        moveDownWhenShown = true
                        true
                    } else {
                        false
                    }
                },
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = index == selected,
                    onFocus = { selected = index },
                    modifier = Modifier.focusRequester(tabFocus[index]),
                ) {
                    Text(title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        when (shown) {
            0 -> SearchScreen(vm)
            1 -> QueueScreen(vm)
            2 -> LibraryContent(vm, MediaKind.MOVIE)
            3 -> LibraryContent(vm, MediaKind.SHOW)
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
                    onToggleSeason = vm::toggleSeason,
                    onToggleAllSeasons = vm::toggleAllSeasons,
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
