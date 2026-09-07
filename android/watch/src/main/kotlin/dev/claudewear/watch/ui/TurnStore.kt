package dev.claudewear.watch.ui

import dev.claudewear.shared.GatewayEvent
import dev.claudewear.shared.RelayStatus
import dev.claudewear.shared.RelayedEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The three words on the screen, plus the two states that interrupt them. */
enum class Phase { Idle, Listening, Thinking, Approval, Speaking, Error }

data class Approval(val turnId: String, val approvalId: String, val action: String, val detail: String, val highRisk: Boolean)

data class WatchState(
    val phase: Phase = Phase.Idle,
    val requestId: String? = null,
    val turnId: String? = null,
    val heard: String = "",
    val answer: String = "",
    val activity: String = "",
    val approval: Approval? = null,
    val error: String = "",
    val phone: RelayStatus = RelayStatus(state = "unknown"),
)

/**
 * Pure state: relayed events in, screen state out. Events for a request we are no longer
 * waiting on are dropped, which is what makes cancel and a fresh tap safe.
 */
class TurnStore {
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state

    /** The final answer text, once per turn, for whoever speaks it. */
    private val _speak = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val speak: SharedFlow<String> = _speak

    fun begin(requestId: String, phase: Phase) {
        _state.update { WatchState(phase = phase, requestId = requestId, phone = it.phone) }
    }

    fun listeningDone() = _state.update { if (it.phase == Phase.Listening) it.copy(phase = Phase.Thinking) else it }

    fun fail(message: String) = _state.update { it.copy(phase = Phase.Error, error = message, approval = null) }

    fun reset() = _state.update { WatchState(phone = it.phone) }

    fun spoken() = _state.update { if (it.phase == Phase.Speaking) it.copy(phase = Phase.Idle) else it }

    fun onStatus(status: RelayStatus) = _state.update { it.copy(phone = status) }

    fun onRelayed(relayed: RelayedEvent) {
        val current = _state.value
        if (relayed.requestId != current.requestId) return
        onEvent(relayed.event())
    }

    fun onEvent(e: GatewayEvent) {
        _state.update { s ->
            when (e) {
                is GatewayEvent.TurnStarted -> s.copy(turnId = e.turnId, phase = Phase.Thinking)
                is GatewayEvent.InputTranscript -> s.copy(heard = e.text)
                is GatewayEvent.OutputDelta -> s.copy(answer = s.answer + e.text)
                is GatewayEvent.ToolStarted -> s.copy(activity = e.name)
                is GatewayEvent.ToolCompleted -> s.copy(activity = "")
                is GatewayEvent.ApprovalRequired -> s.copy(phase = Phase.Approval, approval = Approval(e.turnId, e.approvalId, e.action, e.detail, e.highRisk))
                is GatewayEvent.ApprovalResolved -> s.copy(phase = Phase.Thinking, approval = null)
                is GatewayEvent.OutputDone -> s.copy(answer = e.text, phase = Phase.Speaking, activity = "")
                is GatewayEvent.TurnCompleted -> when (e.status) {
                    "ok" -> if (s.phase == Phase.Speaking) s else s.copy(phase = Phase.Idle)
                    "cancelled" -> s.copy(phase = Phase.Idle, approval = null)
                    else -> if (s.phase == Phase.Error) s else s.copy(phase = Phase.Error, error = s.error.ifEmpty { "Hermes could not answer" }, approval = null)
                }
                is GatewayEvent.Error -> s.copy(phase = Phase.Error, error = e.message, approval = null)
                is GatewayEvent.SpeechChunk, is GatewayEvent.Unknown -> s
            }
        }
        if (e is GatewayEvent.OutputDone && e.text.isNotBlank()) _speak.tryEmit(e.text)
    }
}
