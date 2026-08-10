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
 * One chat: what has happened, and what you can do about it from a wrist.
 *
 * Anything the agent is blocked on leads the screen as a chip that opens its card, and the
 * same block stays in the transcript in its place in the conversation. Dictation is the
 * first-class way in — the keyboard is still there, but this is a product for answering
 * without looking, and the mic is the path that does that.
 */
@Composable
fun ChatScreen(
    state: WatchState,
    sessionId: String,
    onPrompt: (String) -> Unit,
    onInterrupt: () -> Unit,
    onModes: () -> Unit,
    onOpenRequest: (requestId: String) -> Unit = {},
    onDictate: (prompt: String, onResult: (String) -> Unit) -> Unit = { _, _ -> },
) {
    val session = state.session(sessionId)
    val transcript = state.transcript(sessionId)
    val blocked = state.pending(sessionId)
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

            // What it is waiting for comes before the history of how it got there.
            items(blocked) { request ->
                Chip(
                    onClick = { onOpenRequest(request.requestId) },
                    label = { Text(if (request is PendingRequest.Ask) "Answer" else "Allow or deny", maxLines = 1) },
                    secondaryLabel = { Text(request.summary, maxLines = 2) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            items(transcript) { line -> TranscriptRow(line, stillWaiting = line.requestId in state.pending) }

            item {
                CompactChip(
                    onClick = { onDictate("Say something") { onPrompt(it) } },
                    label = { Text("Speak") },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
            item { Field(value = draft, onValueChange = { draft = it }, hint = "or type") }
            item {
                Chip(
                    onClick = {
                        onPrompt(draft.trim())
                        draft = ""
                    },
                    label = { Text("Send") },
                    enabled = draft.isNotBlank(),
                    colors = ChipDefaults.secondaryChipColors(),
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
private fun TranscriptRow(line: TranscriptLine, stillWaiting: Boolean) {
    val colour = when (line.kind) {
        TranscriptLine.Kind.CLAUDE -> MaterialTheme.colors.onSurface
        TranscriptLine.Kind.YOU -> MaterialTheme.colors.onSurfaceVariant
        TranscriptLine.Kind.WAITING -> MaterialTheme.colors.primary
        TranscriptLine.Kind.RESULT -> MaterialTheme.colors.onSurface
        TranscriptLine.Kind.PROBLEM -> MaterialTheme.colors.error
    }
    // A block that is still blocking says so; one that has been dealt with — here, from the
    // CLI, or by the agent giving up — is history, and history should not still be shouting.
    val prefix = when {
        line.kind == TranscriptLine.Kind.YOU -> "you: "
        line.kind == TranscriptLine.Kind.WAITING && stillWaiting -> "waiting on you: "
        line.kind == TranscriptLine.Kind.WAITING -> "asked: "
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
