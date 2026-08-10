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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * Reasons a deny can carry without dictating one. Claude sees the deny message, so it adapts
 * instead of retrying the same thing — which makes the reason worth a tap even when you are
 * in a hurry. Shared with the notification's Deny action, so the shade offers the same list.
 */
internal val DENY_REASONS = listOf(
    "not now",
    "wrong directory",
    "do it a different way",
    "explain first",
)

/**
 * Allow or deny a tool call from the wrist.
 *
 * The command is rendered in full and never summarised or truncated. Approving what you
 * cannot see is the failure mode this product designs against, and a 1.5" screen is where
 * that goes wrong most easily — so the command gets the room and the buttons come after it,
 * below the fold if that is what it takes.
 *
 * "Always allow" appears only when the bridge actually sent a rule it could persist, and it
 * names the rule. A button that claims to remember something and does not is worse than no
 * button.
 */
@Composable
fun PermissionScreen(
    request: PendingRequest.Permission?,
    sessionName: String,
    onAllow: () -> Unit,
    onAlways: () -> Unit,
    onDeny: (reason: String?) -> Unit,
    onDictate: (prompt: String, onResult: (String) -> Unit) -> Unit,
    /**
     * The card's second half, which is otherwise only reachable by tapping Deny. It is a
     * parameter so [Gallery] can pose it, and so the reasons are a screenshot CI checks
     * rather than a list nobody looks at until they are denying something in a hurry.
     */
    initiallyDenying: Boolean = false,
) {
    val listState = rememberScalingLazyListState()

    if (request == null) {
        GoneCard("This request is answered", listState)
        return
    }

    var denying by rememberSaveable(request.requestId) { mutableStateOf(initiallyDenying) }

    WatchScaffold(listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Caption(sessionName) }
            item { ScreenTitle(request.tool) }
            // The actual command or path, verbatim. No maxLines: a command you can only see
            // half of is a command you cannot approve.
            item { Text(request.display, style = MaterialTheme.typography.body2) }

            if (!denying) {
                item {
                    Chip(
                        onClick = onAllow,
                        label = { Text("Allow") },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                request.alwaysRules.firstOrNull()?.let { rule ->
                    item {
                        Chip(
                            onClick = onAlways,
                            label = { Text("Always allow", maxLines = 1) },
                            // The rule, not a promise about one: this is what gets written to
                            // .claude/settings.local.json and stops the buzzing for good.
                            secondaryLabel = { Text(rule, maxLines = 1) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item {
                    Chip(
                        onClick = { denying = true },
                        label = { Text("Deny") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item { Caption("why? Claude sees this and adapts") }

                DENY_REASONS.forEach { reason ->
                    item {
                        Chip(
                            onClick = { onDeny(reason) },
                            label = { Text(reason, maxLines = 1) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item {
                    CompactChip(
                        onClick = { onDictate("Why not?", onDeny) },
                        label = { Text("Say why…") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
                item {
                    CompactChip(
                        onClick = { onDeny(null) },
                        label = { Text("Just deny") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }
    }
}
