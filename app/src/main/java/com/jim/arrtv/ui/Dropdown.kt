package com.jim.arrtv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

/**
 * A button showing the current choice, which opens a list of the [options] to pick from. The list opens with
 * focus on the current choice, and focus goes back to the button when it closes.
 */
@Composable
fun <T> Dropdown(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    val choiceFocus = remember { FocusRequester() }

    Box {
        OutlinedButton(
            onClick = {
                open = true
                opened = true
            },
            modifier = Modifier.focusRequester(buttonFocus),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty())
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        M3Theme(colorScheme = m3DarkColorScheme()) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    DropdownMenuItem(
                        text = { M3Text(label) },
                        onClick = {
                            open = false
                            if (!isSelected) onSelect(value)
                        },
                        leadingIcon = {
                            // An empty box keeps the labels lined up.
                            if (isSelected) Icon(Icons.Filled.Check, contentDescription = null) else Box(Modifier.width(24.dp))
                        },
                        modifier = if (isSelected) Modifier.focusRequester(choiceFocus) else Modifier,
                    )
                }
            }
        }
    }

    LaunchedEffect(open) {
        runCatching {
            when {
                open -> choiceFocus.requestFocus()
                opened -> buttonFocus.requestFocus()
            }
        }
    }
}
