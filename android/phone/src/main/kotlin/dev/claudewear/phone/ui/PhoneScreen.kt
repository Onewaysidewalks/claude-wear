package dev.claudewear.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun PhoneScreen(
    state: PhoneUiState,
    onSave: (String, String) -> Unit,
    onCheck: () -> Unit,
    onRefreshWatches: () -> Unit,
    onPrompt: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onDecide: (String) -> Unit,
) {
    var url by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var token by rememberSaveable(state.token) { mutableStateOf(state.token) }
    var confirmHighRisk by rememberSaveable { mutableStateOf(false) }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Hermes Gateway", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Gateway URL (tailnet, e.g. http://100.x.y.z:8731)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Device token (hg1_…)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(url, token) }) { Text("Save & check") }
                    OutlinedButton(onClick = onCheck, enabled = state.configured) { Text("Check") }
                }
                Text("Connection: ${state.connection}", style = MaterialTheme.typography.bodySmall)
                Text("Relay for watch: ${state.relayState}", style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Text("Watches", style = MaterialTheme.typography.titleMedium)
                if (state.watchNodes.isEmpty()) Text("none connected", style = MaterialTheme.typography.bodySmall)
                state.watchNodes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = onRefreshWatches) { Text("Refresh") }

                HorizontalDivider()
                Text("Talk to Hermes (text)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = state.prompt, onValueChange = onPrompt, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSend, enabled = state.configured && !state.busy && state.prompt.isNotBlank()) { Text("Send") }
                    OutlinedButton(onClick = onCancel, enabled = state.busy) { Text("Cancel") }
                }
                if (state.answer.isNotEmpty()) {
                    Card { Text(state.answer, modifier = Modifier.padding(12.dp)) }
                }
                state.transcript.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        state.approval?.let { a ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(if (a.highRisk) "High-risk action: confirm" else "Hermes asks") },
                text = { Text("${a.action}\n${a.detail}".trim()) },
                confirmButton = {
                    TextButton(onClick = { if (a.highRisk && !confirmHighRisk) confirmHighRisk = true else { confirmHighRisk = false; onDecide("allow") } }) {
                        Text(if (a.highRisk && !confirmHighRisk) "Allow…" else if (a.highRisk) "Yes, really allow" else "Allow")
                    }
                },
                dismissButton = { TextButton(onClick = { confirmHighRisk = false; onDecide("deny") }) { Text("Deny") } },
            )
        }
    }
}
