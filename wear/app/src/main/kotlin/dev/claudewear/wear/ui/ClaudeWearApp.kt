package dev.claudewear.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.claudewear.protocol.SessionSummary

/**
 * The M0 stub UI: enough to pair, see the chats, and watch a turn arrive.
 *
 * M2 replaces this with the real Pair / Sessions / Chat screens and M3 adds the question
 * and permission cards. Nothing here is meant to survive; it exists so the E2E loop is a
 * real app talking to a real bridge rather than a test harness pretending to be one.
 */
@Composable
fun ClaudeWearApp(
    paired: Boolean,
    state: WatchState,
    defaultBridgeUrl: String,
    pairingError: String?,
    onPair: (baseUrl: String, code: String) -> Unit,
    onNewSession: (cwd: String) -> Unit,
) {
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            if (paired) SessionsScreen(state, onNewSession) else PairScreen(defaultBridgeUrl, pairingError, onPair)
        }
    }
}

@Composable
private fun PairScreen(defaultBridgeUrl: String, error: String?, onPair: (String, String) -> Unit) {
    var url by remember { mutableStateOf(defaultBridgeUrl) }
    var code by remember { mutableStateOf("") }

    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        item { Text("Pair with your bridge", style = MaterialTheme.typography.title3) }
        item { Field(value = url, onValueChange = { url = it }, numeric = false) }
        item { Text("8-digit code", style = MaterialTheme.typography.caption1) }
        item { Field(value = code, onValueChange = { code = it.filter(Char::isDigit).take(8) }, numeric = true) }
        item {
            Chip(
                onClick = { onPair(url.trim(), code) },
                label = { Text("Pair") },
                enabled = code.length == 8,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (error != null) {
            item { Text(error, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.error) }
        }
    }
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, numeric: Boolean) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Uri,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2B2B2B))
            .padding(8.dp),
    )
}

@Composable
private fun SessionsScreen(state: WatchState, onNewSession: (String) -> Unit) {
    // Typed rather than picked: which project roots the bridge exposes is still open, and
    // it wants to be a list the bridge sends. M1.
    var cwd by remember { mutableStateOf("") }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                if (state.connected) "${state.sessions.size}/${state.maxSessions} chats" else "connecting…",
                style = MaterialTheme.typography.caption1,
            )
        }
        state.lastTurn?.let { turn ->
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(turn.sessionName, style = MaterialTheme.typography.title3)
                    Text(turn.summary, style = MaterialTheme.typography.body2)
                }
            }
        }
        items(state.sessions) { session -> SessionRow(session) }
        item { Field(value = cwd, onValueChange = { cwd = it }, numeric = false) }
        item {
            Chip(
                onClick = { onNewSession(cwd.trim()) },
                label = { Text("New chat") },
                enabled = cwd.isNotBlank(),
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(state.transcript.takeLast(5)) { line ->
            Text(line, style = MaterialTheme.typography.caption2)
        }
        state.error?.let { message ->
            item { Text(message, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.error) }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary) {
    // The name leads, because "may I run npm test?" is not answerable when two chats are waiting.
    Chip(
        onClick = {},
        label = { Text(session.name) },
        secondaryLabel = { Text(badge(session)) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun badge(session: SessionSummary): String = when {
    session.pendingRequestIds.isNotEmpty() -> "awaiting you"
    else -> session.state.name.lowercase()
}
