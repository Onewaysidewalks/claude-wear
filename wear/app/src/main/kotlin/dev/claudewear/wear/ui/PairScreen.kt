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
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

private const val CODE_LENGTH = 8

/**
 * Exchange the code the bridge printed for a device-scoped token.
 *
 * The address is typed once and remembered; the code is single-use with a five minute TTL,
 * which is adequate precisely because the bridge is not reachable from outside your
 * network. That assumption is the load-bearing one in the whole security posture, so the
 * screen says where it is connecting rather than hiding it behind a "connect" button.
 */
@Composable
fun PairScreen(
    defaultBridgeUrl: String,
    pairing: PairingState,
    onPair: (baseUrl: String, code: String) -> Unit,
) {
    var url by rememberSaveable(defaultBridgeUrl) { mutableStateOf(defaultBridgeUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(listState) }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { ScreenTitle("Pair with your bridge") }
            item { Caption("the address it printed") }
            item { Field(value = url, onValueChange = { url = it }, hint = "http://…") }
            item { Caption("the $CODE_LENGTH-digit code") }
            item {
                Field(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(CODE_LENGTH) },
                    hint = "00000000",
                    numeric = true,
                )
            }
            item {
                Chip(
                    onClick = { onPair(url.trim(), code) },
                    label = { Text(if (pairing.busy) "Pairing…" else "Pair") },
                    enabled = code.length == CODE_LENGTH && url.isNotBlank() && !pairing.busy,
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            pairing.error?.let { item { Problem(it) } }
            item {
                Caption(
                    "The bridge runs shell commands on the machine holding your code. " +
                        "Pair it over your own network or a tailnet, never the open internet.",
                )
            }
        }
    }
}
