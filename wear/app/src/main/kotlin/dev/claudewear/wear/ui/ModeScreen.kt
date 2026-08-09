package dev.claudewear.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import dev.claudewear.protocol.PermissionMode

/**
 * The permission mode, and what each one costs you.
 *
 * This is the most dangerous control in the product — a wrist tap that decides how much of
 * a shell-running agent you still see — so every option says plainly what it gives up. In
 * particular `bypassPermissions` is described by what you lose, not by what it enables:
 * the agent stops asking, so the watch stops buzzing, so you stop knowing.
 */
@Composable
fun ModeScreen(current: PermissionMode?, onPick: (PermissionMode) -> Unit) {
    val listState = rememberScalingLazyListState()

    WatchScaffold(listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ScreenTitle("Permissions") }
            item { Caption("how much this chat asks you first") }

            items(PermissionMode.entries.toList()) { mode ->
                Chip(
                    onClick = { onPick(mode) },
                    label = { Text(mode.wire, maxLines = 1) },
                    secondaryLabel = { Text(describe(mode)) },
                    colors = if (mode == current) {
                        ChipDefaults.primaryChipColors()
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Caption(
                    "bypassPermissions also has to be enabled on the bridge itself. " +
                        "If it is not, this chat keeps the mode it has and says so.",
                )
            }
        }
    }
}

private fun describe(mode: PermissionMode): String = when (mode) {
    PermissionMode.DEFAULT -> "asks before anything risky"
    PermissionMode.ACCEPT_EDITS -> "edits files unasked; commands still ask"
    PermissionMode.PLAN -> "reads and plans, changes nothing"
    PermissionMode.BYPASS_PERMISSIONS -> "stops asking — and stops buzzing you"
}
