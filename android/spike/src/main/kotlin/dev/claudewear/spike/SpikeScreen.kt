package dev.claudewear.spike

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text

@Composable
fun SpikeScreen(
    status: AssistantStatus?,
    onRequestRole: () -> Unit,
    onOpenDefaultApps: () -> Unit,
    onOpenVoiceInput: () -> Unit,
    onClear: () -> Unit,
) {
    val entries by InvocationLog.entries.collectAsState()
    val listState = rememberScalingLazyListState()
    MaterialTheme {
        Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
            ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                item { Text("Hermes spike", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
                item {
                    val s = status
                    Text(
                        text = if (s == null) "reading…" else buildString {
                            append("${s.manufacturer} ${s.model}\n")
                            append("Android ${s.release} (API ${s.sdk})\n")
                            append("${s.display}\n")
                            append("role available: ${s.roleAvailable}\n")
                            append("role held: ${s.roleHeld}\n")
                            append("assistant: ${s.assistantSetting}\n")
                            append("vis: ${s.voiceInteractionSetting}")
                        },
                        fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    )
                }
                item { Action("Request role", onRequestRole) }
                item { Action("Default apps", onOpenDefaultApps) }
                item { Action("Voice input", onOpenVoiceInput) }
                item { Action("Clear log", onClear) }
                item { Text("Invocations (${entries.size})", style = MaterialTheme.typography.caption1) }
                items(entries.asReversed()) { e ->
                    Text("${e.at} ${e.source}\n${e.detail}", fontSize = 9.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
