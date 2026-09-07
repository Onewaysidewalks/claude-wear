package dev.claudewear.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

private val Listening = Color(0xFFE53935)
private val Thinking = Color(0xFFFFB300)
private val Speaking = Color(0xFF43A047)
private val Quiet = Color(0xFF0F766E)

/** Listen, think, speak. One big button; the rest is what Hermes heard and said. */
@Composable
fun WatchScreen(
    state: WatchState,
    onListen: () -> Unit,
    onType: () -> Unit,
    onDecide: (String) -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    MaterialTheme {
        when (state.phase) {
            Phase.Approval -> ApprovalScreen(state.approval!!, onDecide)
            Phase.Error -> ErrorScreen(state, onDismissError, onListen)
            else -> TurnScreen(state, onListen, onType, onCancel)
        }
    }
}

@Composable
private fun TurnScreen(state: WatchState, onListen: () -> Unit, onType: () -> Unit, onCancel: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val (label, color) = when (state.phase) {
        Phase.Listening -> "Listening" to Listening
        Phase.Thinking -> (state.activity.ifEmpty { "Thinking" }) to Thinking
        Phase.Speaking -> "Speaking" to Speaking
        else -> "Hermes" to Quiet
    }
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text(label, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
        item {
            Button(
                onClick = if (state.phase == Phase.Idle || state.phase == Phase.Listening) onListen else onCancel,
                colors = ButtonDefaults.buttonColors(backgroundColor = color),
                modifier = Modifier.size(64.dp),
            ) {
                Text(
                    when (state.phase) {
                        Phase.Listening -> "■"
                        Phase.Thinking, Phase.Speaking -> "✕"
                        else -> "●"
                    },
                    fontSize = 22.sp,
                )
            }
        }
        if (state.heard.isNotEmpty()) item { Text("“${state.heard}”", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        if (state.answer.isNotEmpty()) item { Text(state.answer, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        if (state.phase == Phase.Idle) {
            item { CompactChip(onClick = onType, label = { Text("Speak via keyboard") }, colors = ChipDefaults.secondaryChipColors()) }
            item { Text(phoneLine(state), fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center) }
        }
    }
}

private fun phoneLine(state: WatchState): String = when (state.phone.state) {
    "ready" -> "phone: ready"
    "unconfigured" -> "phone: set up the Hermes app"
    "unauthorized" -> "phone: token rejected"
    "offline" -> "phone: gateway unreachable"
    "connecting" -> "phone: connecting"
    else -> "phone: ?"
}

@Composable
private fun ApprovalScreen(approval: Approval, onDecide: (String) -> Unit) {
    var armed by rememberSaveable(approval.approvalId) { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text(if (approval.highRisk) "High risk. Sure?" else "Allow?", style = MaterialTheme.typography.title3, color = if (approval.highRisk) Listening else Color.White) }
        item { Text(approval.action, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        if (approval.detail.isNotEmpty()) item { Text(approval.detail, fontSize = 11.sp, color = Color.LightGray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(onClick = { onDecide("deny") }, label = { Text("Deny") }, colors = ChipDefaults.secondaryChipColors())
                // High risk: two deliberate taps, never one, never a voice "yes".
                Chip(
                    onClick = { if (approval.highRisk && !armed) armed = true else onDecide("allow") },
                    label = { Text(if (approval.highRisk && !armed) "Allow…" else if (approval.highRisk) "Confirm" else "Allow") },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = if (approval.highRisk) Listening else Speaking),
                )
            }
        }
    }
}

@Composable
private fun ErrorScreen(state: WatchState, onDismiss: () -> Unit, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Hmm", style = MaterialTheme.typography.title3)
        Text(state.error, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactChip(onClick = onDismiss, label = { Text("OK") }, colors = ChipDefaults.secondaryChipColors())
            CompactChip(onClick = { onDismiss(); onRetry() }, label = { Text("Retry") })
        }
    }
}
