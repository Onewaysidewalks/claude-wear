package dev.claudewear.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text

/**
 * Where the new chat runs.
 *
 * The bridge sends the roots it is willing to open, so the normal path is one tap on a
 * directory you already configured. Typing an absolute path on a 1.5" screen is the
 * fallback, and it is only the *only* path when the bridge has no roots configured — which
 * is the shipped default, and the thing `projectRoots` in `config.json` exists to fix.
 */
@Composable
fun NewChatScreen(state: WatchState, onStart: (cwd: String) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val listState = rememberScalingLazyListState()

    WatchScaffold(listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ScreenTitle("New chat") }

            if (state.projectRoots.isEmpty()) {
                item { Caption("this bridge will open any directory it can see") }
            } else {
                item { Caption("your project roots") }
                items(state.projectRoots) { root ->
                    Chip(
                        onClick = { onStart(root) },
                        label = { Text(root.substringAfterLast('/').ifEmpty { root }, maxLines = 1) },
                        secondaryLabel = { Text(root, maxLines = 1) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { Caption("somewhere else") }
            }

            item { Field(value = typed, onValueChange = { typed = it }, hint = "/absolute/path") }
            item {
                Chip(
                    onClick = { onStart(typed.trim()) },
                    label = { Text("Start here") },
                    enabled = typed.isNotBlank(),
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
