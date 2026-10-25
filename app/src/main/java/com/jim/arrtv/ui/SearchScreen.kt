package com.jim.arrtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Text
import com.jim.arrtv.data.MediaKind
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

@Composable
fun SearchScreen(vm: ArrViewModel) {
    var term by rememberSaveable { mutableStateOf("") }
    var focusResults by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submit = {
        keyboard?.hide()
        focusResults = true
        vm.search(term)
    }

    // After a search from the keyboard, jump down to the results so they can be browsed straight away.
    LaunchedEffect(vm.searchResults) {
        if (focusResults && vm.searchResults !is Loadable.Loading) {
            focusResults = false
            if (vm.searchResults is Loadable.Loaded) {
                withFrameNanos {}
                focusManager.moveFocus(FocusDirection.Down)
            }
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaKind.entries.forEach { kind ->
                FilterChip(
                    selected = vm.searchKind == kind,
                    onClick = {
                        vm.chooseSearchKind(kind)
                        vm.search(term)
                    },
                ) { Text(kind.label) }
            }
            Spacer(Modifier.width(12.dp))
            M3Theme(colorScheme = m3DarkColorScheme()) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { androidx.compose.material3.Text("Search ${vm.searchKind.label.lowercase()}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.width(560.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        LoadableContent(
            state = vm.searchResults,
            kind = vm.searchKind,
            onRetry = { vm.search(term) },
            emptyText = "No matches for \"$term\".",
            idleText = "Search for something to add to ${vm.searchKind.service}.",
        ) { results ->
            PosterGrid(results, onClick = vm::openResult)
        }
    }
}
