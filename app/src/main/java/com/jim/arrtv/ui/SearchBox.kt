package com.jim.arrtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val BoxShape = RoundedCornerShape(28.dp)
private val BoxHeight = 56.dp
private val BoxWidth = 560.dp
private const val FocusedScale = 1.04f
private val FocusedBorder = BorderStroke(3.dp, Color.White)
private val FocusedFill = Color.White.copy(alpha = 0.16f)

/** Whether a [SearchBox] is being typed in. [focus] puts focus back on the box, e.g. after something else disappears. */
@Stable
class SearchBoxState {
    var editing by mutableStateOf(false)
        internal set
    internal var refocus by mutableStateOf(false)

    fun focus() {
        refocus = true
    }
}

/**
 * A search box that only brings up the keyboard once you press OK on it, so moving past it with the D-pad doesn't.
 * Hiding the keyboard with Back, or its search key, finishes typing and leaves focus on the box, unless
 * [refocusAfterSearch] is false because [onSearch] moves focus somewhere itself. A Clear button sits to the right
 * whenever there's text. [onClear] runs after the text is emptied; focus stays on the box unless it moves it.
 */
@Composable
fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    state: SearchBoxState = remember { SearchBoxState() },
    refocusAfterSearch: Boolean = true,
    onClear: () -> Unit = { state.focus() },
    /** Anything else for the end of the row, like a count. */
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val editing = state.editing

    fun finish(refocus: Boolean) {
        keyboard?.hide()
        if (refocus) state.refocus = true
        state.editing = false
    }

    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos {} // let the box swap first
            runCatching { fieldFocus.requestFocus() }
        }
    }
    LaunchedEffect(state.refocus, editing) {
        if (state.refocus && !editing) {
            withFrameNanos {} // let the row update first
            runCatching { boxFocus.requestFocus() }
            state.refocus = false
        }
    }
    // Back while the keyboard is up only hides the keyboard, and the app never sees it. So typing stops as soon as the
    // keyboard goes away, rather than needing a second Back. The window insets are the only way to tell.
    val view = LocalView.current
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        var shown = false
        while (true) {
            val visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (visible) {
                shown = true
            } else if (shown) {
                finish(refocus = true)
                break
            }
            delay(100)
        }
    }
    // In case the keyboard was already hidden some other way.
    BackHandler(enabled = editing) { finish(refocus = true) }

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (editing) {
            var hadFocus by remember { mutableStateOf(false) }
            // Looks the same as the focused box, with a cursor.
            Box(
                Modifier
                    .width(BoxWidth)
                    .height(BoxHeight)
                    .graphicsLayer {
                        scaleX = FocusedScale
                        scaleY = FocusedScale
                    }
                    .background(FocusedFill, BoxShape)
                    .border(FocusedBorder, BoxShape),
                contentAlignment = Alignment.CenterStart,
            ) {
                SearchBoxContent {
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) PlaceholderText(placeholder)
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                finish(refocus = refocusAfterSearch)
                                onSearch()
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(fieldFocus)
                                // Back with the keyboard already hidden stops typing, rather than going up to the tab bar.
                                .onPreviewKeyEvent {
                                    if (it.key != Key.Back) return@onPreviewKeyEvent false
                                    if (it.type == KeyEventType.KeyUp) finish(refocus = true)
                                    true
                                }
                                .onFocusChanged {
                                    // Moving off with the D-pad stops typing, without pulling focus back.
                                    if (it.isFocused) hadFocus = true else if (hadFocus && state.editing) finish(refocus = false)
                                },
                        )
                    }
                }
            }
        } else {
            Surface(
                onClick = { state.editing = true },
                modifier = Modifier.width(BoxWidth).height(BoxHeight).focusRequester(boxFocus),
                shape = ClickableSurfaceDefaults.shape(BoxShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    contentColor = Color.White,
                    focusedContainerColor = FocusedFill,
                    focusedContentColor = Color.White,
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), shape = BoxShape),
                    focusedBorder = Border(FocusedBorder, shape = BoxShape),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = FocusedScale),
            ) {
                SearchBoxContent {
                    if (value.isEmpty()) {
                        PlaceholderText(placeholder)
                    } else {
                        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (value.isNotEmpty()) {
            OutlinedButton(onClick = {
                onValueChange("")
                onClear()
            }) { Text("Clear") }
        }
        trailing()
    }
}

@Composable
private fun SearchBoxContent(text: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        text()
    }
}

@Composable
private fun PlaceholderText(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
}
