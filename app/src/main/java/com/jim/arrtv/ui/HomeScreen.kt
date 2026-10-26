package com.jim.arrtv.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
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

    val tabFocus = remember { tabs.map { FocusRequester() } }
    var confirmExit by rememberSaveable { mutableStateOf(false) }

    // Back from a screen goes up to its tab, and Back on the tab bar asks before leaving the app. Left alone, Compose
    // treats Back as "leave this focus group", which lands on whichever tab is nearest (and switches to it), or quits.
    // That acts on key down, so both halves are taken here, and the action waits for key up so the release can't
    // reach the exit popup. It only counts if the press also started here.
    var backDown by remember { mutableStateOf(false) }
    fun handleBack(event: KeyEvent, onBack: () -> Unit): Boolean {
        if (event.key != Key.Back) return false
        when (event.type) {
            KeyEventType.KeyDown -> backDown = true
            KeyEventType.KeyUp -> if (backDown) {
                backDown = false
                onBack()
            }
        }
        return true
    }
    fun focusTab() = runCatching { tabFocus[selected].requestFocus() }
    // Bumped by Back from a screen, so a poster grid scrolled a long way down goes back to the top.
    var scrollToTop by remember { mutableIntStateOf(0) }
    // Only reached when nothing has focus (e.g. while a screen is loading).
    BackHandler { focusTab() }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        // Coming up from a screen, land on the tab you're on rather than whichever tab is nearest.
        TabRow(
            selectedTabIndex = selected,
            modifier = Modifier
                .focusProperties { enter = { tabFocus[selected] } }
                .focusGroup()
                .onPreviewKeyEvent {
                    if (handleBack(it) { confirmExit = true }) return@onPreviewKeyEvent true
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
        // After the screen's own handling, so a search box being typed in can take Back first.
        Box(Modifier.fillMaxSize().onKeyEvent { handleBack(it) {
            focusTab()
            scrollToTop++
        } }) {
            when (shown) {
                0 -> SearchScreen(vm, scrollToTop)
                1 -> QueueScreen(vm)
                2 -> LibraryContent(vm, MediaKind.MOVIE, scrollToTop)
                3 -> LibraryContent(vm, MediaKind.SHOW, scrollToTop)
                SETTINGS_TAB -> SettingsScreen(vm.settings, vm::saveSettings)
            }
        }
    }

    if (confirmExit) ConfirmExit(onDismiss = { confirmExit = false })

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
                    onTrailer = { vm.openTrailer(state.result.trailer) },
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

    // Over the detail page it was started from. Back returns to that page.
    vm.releaseSearch?.let { state ->
        Dialog(onDismissRequest = vm::closeReleaseSearch, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { ReleaseScreen(state, vm) }
        }
    }

    // Last, so it opens over the add or detail page it was started from. Back returns to that page.
    vm.trailer?.let { state ->
        Dialog(onDismissRequest = vm::closeTrailer, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            TrailerScreen(state, onFailed = vm::nextTrailer, onClose = vm::closeTrailer)
        }
    }
}

/** "Exit arr-tv?" Focus starts on No, so pressing OK by mistake keeps you in the app. Back also means No. */
@Composable
private fun ConfirmExit(onDismiss: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val noFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(32.dp)) {
                Text("Exit arr-tv?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { activity?.finish() }) { Text("Yes") }
                    Button(onClick = onDismiss, modifier = Modifier.focusRequester(noFocus)) { Text("No") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { noFocus.requestFocus() } }
}
