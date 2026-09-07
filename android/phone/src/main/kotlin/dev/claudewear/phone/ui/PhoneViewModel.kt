package dev.claudewear.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.claudewear.phone.HermesPhoneApp
import dev.claudewear.phone.data.GatewayConfig
import dev.claudewear.phone.data.GatewaySettings
import dev.claudewear.shared.GatewayEvent
import dev.claudewear.shared.RelayStatus
import dev.claudewear.shared.WatchProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class PendingApproval(val turnId: String, val approvalId: String, val action: String, val detail: String, val highRisk: Boolean)

data class PhoneUiState(
    val baseUrl: String = "",
    val token: String = "",
    val configured: Boolean = false,
    val connection: String = "not checked",
    val relayState: String = "unconfigured",
    val watchNodes: List<String> = emptyList(),
    val prompt: String = "",
    val busy: Boolean = false,
    val transcript: List<String> = emptyList(),
    val answer: String = "",
    val approval: PendingApproval? = null,
)

/** Settings, a connection check, a text console for milestone 2, and the watch diagnostics. */
class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<HermesPhoneApp>()
    private val _state = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = _state
    private var turn: Job? = null

    init {
        viewModelScope.launch {
            app.settings.config.collect { c -> _state.update { it.copy(baseUrl = c.baseUrl, token = c.token, configured = c.configured) } }
        }
        viewModelScope.launch {
            app.relay.status.collect { s: RelayStatus -> _state.update { it.copy(relayState = s.state + if (s.detail.isNotEmpty()) " (${s.detail})" else "") } }
        }
        refreshWatches()
    }

    fun save(baseUrl: String, token: String) {
        val candidate = GatewayConfig(GatewaySettings.normalize(baseUrl), token.trim())
        candidate.problem()?.let { why ->
            _state.update { it.copy(connection = "not saved: $why") }
            return
        }
        app.settings.save(baseUrl, token)
        checkConnection()
    }

    fun checkConnection() {
        viewModelScope.launch {
            _state.update { it.copy(connection = "checking…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val h = app.client.health()
                    val who = app.client.whoami()
                    "ok: gateway ${h.version}, brain ${h.brainKind}${if (h.brainReachable) "" else " (unreachable)"}, stt ${h.stt}, device \"$who\""
                }.getOrElse { "failed: ${it.message}" }
            }
            _state.update { it.copy(connection = result) }
            app.relay.setStatus(RelayStatus(if (result.startsWith("ok")) "ready" else "offline", result.take(80)))
        }
    }

    fun refreshWatches() {
        viewModelScope.launch {
            val nodes = runCatching {
                val all = Wearable.getNodeClient(app).connectedNodes.await()
                val relayCapable = runCatching {
                    Wearable.getCapabilityClient(app).getCapability(WatchProtocol.CAPABILITY_RELAY, CapabilityClient.FILTER_ALL).await().nodes.map { it.id }
                }.getOrDefault(emptySet<String>())
                all.map { n -> "${n.displayName} (${if (n.isNearby) "nearby" else "remote"}${if (n.id in relayCapable) ", relay-capable" else ""})" }
            }.getOrElse { listOf("data layer unavailable: ${it.message}") }
            _state.update { it.copy(watchNodes = nodes) }
        }
    }

    fun setPrompt(p: String) = _state.update { it.copy(prompt = p) }

    /** Milestone 2: phone to Hermes, text only. Same client the relay uses. */
    fun send() {
        val text = _state.value.prompt.trim()
        if (text.isEmpty() || _state.value.busy) return
        turn = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, transcript = listOf("> $text"), answer = "", approval = null, prompt = "") }
            app.client.textTurn(text, session = "phone")
                .catch { e -> log("error: ${e.message}") }
                .collect { e -> onEvent(e) }
            _state.update { it.copy(busy = false) }
        }
    }

    fun cancel() {
        val turnId = _state.value.approval?.turnId
        turn?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            if (turnId != null) runCatching { app.client.cancel(turnId) }
            _state.update { it.copy(busy = false, approval = null) }
        }
    }

    fun decide(decision: String) {
        val a = _state.value.approval ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.client.approve(a.turnId, a.approvalId, decision) }.onFailure { log("approval failed: ${it.message}") }
            _state.update { it.copy(approval = null) }
        }
    }

    private fun onEvent(e: GatewayEvent) {
        when (e) {
            is GatewayEvent.TurnStarted -> log("turn ${e.turnId.takeLast(6)} started")
            is GatewayEvent.OutputDelta -> _state.update { it.copy(answer = it.answer + e.text) }
            is GatewayEvent.ToolStarted -> log("tool ${e.name} ${e.summary}")
            is GatewayEvent.ToolCompleted -> log("tool ${e.name} ${if (e.ok) "ok" else "failed"} ${e.summary}")
            is GatewayEvent.ApprovalRequired -> _state.update { it.copy(approval = PendingApproval(e.turnId, e.approvalId, e.action, e.detail, e.highRisk)) }
            is GatewayEvent.ApprovalResolved -> log("approval ${e.decision} (${e.by})")
            is GatewayEvent.OutputDone -> _state.update { it.copy(answer = e.text) }
            is GatewayEvent.TurnCompleted -> log("turn ${e.status}")
            is GatewayEvent.Error -> log("error ${e.code}: ${e.message}")
            is GatewayEvent.InputTranscript -> log("heard: ${e.text}")
            is GatewayEvent.SpeechChunk, is GatewayEvent.Unknown -> Unit
        }
    }

    private fun log(line: String) = _state.update { it.copy(transcript = it.transcript + line) }
}
