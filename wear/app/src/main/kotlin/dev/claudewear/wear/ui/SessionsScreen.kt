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
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.claudewear.protocol.SessionState

/**
 * Who needs you, in order.
 *
 * Awaiting chats lead and are the only ones drawn in the accent colour, because the list
 * exists to answer one question and answering it should not require reading.
 */
@Composable
fun SessionsScreen(
    state: WatchState,
    onOpen: (sessionId: String) -> Unit,
    onNewChat: () -> Unit,
    onUnpair: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(listState) }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Caption(header(state)) }

            items(state.sessions, key = { it.sessionId }) { session ->
                SessionRow(session, onClick = { onOpen(session.sessionId) })
            }

            if (state.sessions.isEmpty() && state.connected) {
                item { Caption("no chats yet") }
            }

            item {
                Chip(
                    onClick = onNewChat,
                    label = { Text("New chat") },
                    enabled = state.connected && state.canOpenAnother,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!state.canOpenAnother) {
                // The cap is the bridge's, and it is there because N chats is N agent loops
                // and N token spends on the machine you are also working on.
                item { Caption("${state.maxSessions} chats is this bridge's limit") }
            }

            state.error?.let { item { Problem(it) } }

            item { CompactChip(onClick = onUnpair, label = { Text("Unpair") }) }
        }
    }
}

private fun header(state: WatchState): String = when (state.connection) {
    WatchState.Connection.CONNECTING -> "connecting…"
    WatchState.Connection.OFFLINE -> "offline — retrying"
    WatchState.Connection.CONNECTED -> "${state.sessions.size}/${state.maxSessions} chats"
}

@Composable
private fun SessionRow(session: SessionView, onClick: () -> Unit) {
    // The name leads, because "may I run npm test?" is not answerable when two chats are waiting.
    Chip(
        onClick = onClick,
        label = { Text(session.name, maxLines = 1) },
        secondaryLabel = { Text(badge(session), maxLines = 1) },
        icon = if (session.state == SessionState.WORKING) {
            { Spinner() }
        } else {
            null
        },
        colors = if (session.awaiting) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun badge(session: SessionView): String = when {
    session.pendingRequestIds.size > 1 -> "${session.pendingRequestIds.size} things need you"
    session.awaiting -> "needs you"
    else -> session.state.name.lowercase()
}
