package dev.claudewear.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import dev.claudewear.protocol.AskQuestion

/**
 * Answer an AskUserQuestion from the wrist.
 *
 * One section per question — `header` is the title, and it is at most twelve characters
 * because the SDK made it for exactly this screen size. Options are chips; a multiSelect
 * question gets toggles and waits for Send. A single single-select question sends on the
 * tap, because making you confirm a one-question card is a tap that buys nothing.
 *
 * Two ways out that the protocol already carries and Claude Desktop also offers:
 * "Something else…" dictates a free-text answer to that question — the transcript goes in as
 * the value, so Claude gets the answer rather than the word "Other" — and "Say something
 * else" abandons the options entirely and just talks.
 */
@Composable
fun QuestionScreen(
    request: PendingRequest.Ask?,
    sessionName: String,
    onAnswer: (Map<String, String>) -> Unit,
    onRespond: (String) -> Unit,
    onDictate: (prompt: String, onResult: (String) -> Unit) -> Unit,
) {
    val listState = rememberScalingLazyListState()

    if (request == null) {
        GoneCard("This question is answered", listState)
        return
    }

    // Question text -> the labels picked for it. A multiSelect question keeps several.
    val picked = remember(request.requestId) { mutableStateMapOf<String, List<String>>() }
    val complete = request.questions.all { !picked[it.question].isNullOrEmpty() }
    val instant = request.questions.size == 1 && !request.questions.single().multiSelect

    fun send() = onAnswer(
        // Every question answered, keyed by its text and valued by the labels — joined for a
        // multiSelect. That shape is the SDK's, not ours.
        request.questions.associate { it.question to picked[it.question].orEmpty().joinToString(", ") },
    )

    WatchScaffold(listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Caption(sessionName) }

            request.questions.forEach { question ->
                item { QuestionHeader(question) }

                question.options.forEach { option ->
                    item {
                        OptionChip(
                            label = option.label,
                            description = option.description,
                            selected = option.label in picked[question.question].orEmpty(),
                            multiSelect = question.multiSelect,
                            onClick = {
                                picked[question.question] = pick(
                                    current = picked[question.question].orEmpty(),
                                    label = option.label,
                                    multiSelect = question.multiSelect,
                                )
                                if (instant) send()
                            },
                        )
                    }
                }

                item {
                    CompactChip(
                        onClick = {
                            onDictate(question.header) { spoken ->
                                picked[question.question] = listOf(spoken)
                                if (instant) send()
                            }
                        },
                        label = { Text("Something else…") },
                        // Quieter than the options: it is the way out, not the answer.
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }

            if (!instant) {
                item {
                    Chip(
                        onClick = { send() },
                        label = { Text("Send") },
                        enabled = complete,
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                CompactChip(
                    onClick = { onDictate("Say something else", onRespond) },
                    label = { Text("Say something else") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

/**
 * Toggling rather than replacing on a multiSelect, because tapping a chip you already picked
 * can only mean you did not mean it.
 */
internal fun pick(current: List<String>, label: String, multiSelect: Boolean): List<String> = when {
    !multiSelect -> listOf(label)
    label in current -> current - label
    else -> current + label
}

/**
 * A column, not three loose composables: a `ScalingLazyColumn` item is a Box, so siblings
 * drawn straight into one are drawn on top of each other.
 */
@Composable
private fun QuestionHeader(question: AskQuestion) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenTitle(question.header)
        Text(question.question, style = MaterialTheme.typography.body2)
        if (question.multiSelect) Caption("pick as many as apply")
    }
}

@Composable
private fun OptionChip(
    label: String,
    description: String?,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    if (multiSelect) {
        ToggleChip(
            checked = selected,
            onCheckedChange = { onClick() },
            label = { Text(label, maxLines = 1) },
            secondaryLabel = description?.let { { Text(it, maxLines = 1) } },
            toggleControl = {
                Icon(
                    imageVector = ToggleChipDefaults.checkboxIcon(checked = selected),
                    contentDescription = if (selected) "picked" else "not picked",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Chip(
            onClick = onClick,
            label = { Text(label, maxLines = 1) },
            secondaryLabel = description?.let { { Text(it, maxLines = 1) } },
            colors = if (selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A card whose request is gone — answered from the CLI, from a phone, or cancelled by the
 * agent while this was on screen. Saying so beats a card that silently stops working.
 */
@Composable
internal fun GoneCard(title: String, listState: ScalingLazyListState) {
    WatchScaffold(listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ScreenTitle(title) }
            item { Caption("it was dealt with somewhere else, or the agent gave up waiting") }
        }
    }
}
