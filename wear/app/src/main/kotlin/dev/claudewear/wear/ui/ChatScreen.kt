package dev.claudewear.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.claudewear.protocol.PermissionMode
import dev.claudewear.protocol.SessionState

/**
 * One chat: what has happened, and the two things you can do about it from a wrist.
 *
 * A waiting question or permission is rendered as a transcript line rather than a card.
 * That is deliberate for M2 — a permission card that summarises instead of showing the
 * actual command is worse than no card, because approving what you cannot see is the
 * failure this product designs against. The real cards are M3.
 */
@Composable
fun ChatScreen(
    state: WatchState,
    sessionId: String,
    onPrompt: (String) -> Unit,
    onInterrupt: () -> Unit,
    onModes: () -> Unit,
) {
    val session = state.session(sessionId)
    val transcript = state.transcript(sessionId)
    var draft by rememberSaveable(sessionId) { mutableStateOf("") }
    val listState = rememberScalingLazyListState()

    // New lines arrive while you are reading; the newest one is the one you want.
    LaunchedEffect(transcript.size) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (transcript.isNotEmpty() && last >= 0) listState.animateScrollToItem(last)
    }

    WatchScaffold(listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (session == null) {
                item { ScreenTitle("This chat is gone") }
                item { Caption("it was closed, or the bridge restarted without it") }
                return@ScalingLazyColumn
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ScreenTitle(session.name)
                    Caption("${badgeFor(session)} · ${session.mode.wire}")
                }
            }

            items(transcript) { line -> TranscriptRow(line) }

            item { Field(value = draft, onValueChange = { draft = it }, hint = "say something") }
            item {
                Chip(
                    onClick = {
                        onPrompt(draft.trim())
                        draft = ""
                    },
                    label = { Text("Send") },
                    enabled = draft.isNotBlank(),
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (session.state == SessionState.WORKING || session.awaiting) {
                // Stopping a runaway agent from the wrist is half the reason to wear this.
                item { CompactChip(onClick = onInterrupt, label = { Text("Stop") }) }
            }

            item { CompactChip(onClick = onModes, label = { Text("Permissions") }) }
        }
    }
}

@Composable
private fun TranscriptRow(line: TranscriptLine) {
    val colour = when (line.kind) {
        TranscriptLine.Kind.CLAUDE -> MaterialTheme.colors.onSurface
        TranscriptLine.Kind.YOU -> MaterialTheme.colors.onSurfaceVariant
        TranscriptLine.Kind.WAITING -> MaterialTheme.colors.primary
        TranscriptLine.Kind.RESULT -> MaterialTheme.colors.onSurface
        TranscriptLine.Kind.PROBLEM -> MaterialTheme.colors.error
    }
    val prefix = when (line.kind) {
        TranscriptLine.Kind.YOU -> "you: "
        TranscriptLine.Kind.WAITING -> "waiting on you: "
        else -> ""
    }
    Text(
        prefix + line.text,
        style = if (line.kind == TranscriptLine.Kind.WAITING) {
            MaterialTheme.typography.body2
        } else {
            MaterialTheme.typography.caption2
        },
        color = colour,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun badgeFor(session: SessionView): String = when {
    session.awaiting -> "needs you"
    else -> session.state.name.lowercase()
}

/** The wire spelling, which is what the bridge's docs and flags use. */
internal val PermissionMode.wire: String
    get() = when (this) {
        PermissionMode.DEFAULT -> "default"
        PermissionMode.ACCEPT_EDITS -> "acceptEdits"
        PermissionMode.PLAN -> "plan"
        PermissionMode.BYPASS_PERMISSIONS -> "bypassPermissions"
    }
