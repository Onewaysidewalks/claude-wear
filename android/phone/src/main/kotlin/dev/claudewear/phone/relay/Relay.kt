package dev.claudewear.phone.relay

import android.util.Log
import dev.claudewear.phone.data.GatewayClient
import dev.claudewear.phone.data.TurnGateway
import dev.claudewear.shared.ApprovalAnswer
import dev.claudewear.shared.AudioChannel
import dev.claudewear.shared.AudioSpec
import dev.claudewear.shared.Cancel
import dev.claudewear.shared.GatewayEvent
import dev.claudewear.shared.GatewayEvents
import dev.claudewear.shared.RelayStatus
import dev.claudewear.shared.RelayedEvent
import dev.claudewear.shared.TextTurn
import dev.claudewear.shared.WatchProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns watch messages into gateway turns and gateway events into watch messages. No Android
 * services in here: [send] is whatever delivers bytes to a node (MessageClient in production, a
 * list in tests).
 */
class Relay(
    private val gateway: () -> TurnGateway?,
    private val send: suspend (nodeId: String, path: String, payload: ByteArray) -> Unit,
    private val scope: CoroutineScope,
    /** Re-checks the gateway when the watch asks; null keeps the last known status. */
    private val refresh: (suspend () -> RelayStatus?)? = null,
) {
    private class Active(val nodeId: String, val job: Job, @Volatile var turnId: String? = null)

    private val active = ConcurrentHashMap<String, Active>()
    private val _status = MutableStateFlow(RelayStatus(state = "unconfigured"))
    val status: StateFlow<RelayStatus> = _status
    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight

    fun setStatus(status: RelayStatus) {
        _status.value = status
    }

    fun handleMessage(nodeId: String, path: String, payload: ByteArray) {
        when (path) {
            WatchProtocol.PATH_TURN_TEXT -> {
                val turn = WatchProtocol.json.decodeFromString(TextTurn.serializer(), payload.decodeToString())
                start(nodeId, turn.requestId) { gw -> gw.textTurn(turn.text, turn.session, turn.locale, turn.reset) }
            }
            WatchProtocol.PATH_APPROVAL -> {
                val a = WatchProtocol.json.decodeFromString(ApprovalAnswer.serializer(), payload.decodeToString())
                scope.launch {
                    val gw = gateway() ?: return@launch
                    runCatching { withContext(Dispatchers.IO) { gw.approve(a.turnId, a.approvalId, a.decision) } }
                        .onFailure { Log.w(TAG, "approval ${a.approvalId} failed: $it") }
                }
            }
            WatchProtocol.PATH_CANCEL -> {
                val c = WatchProtocol.json.decodeFromString(Cancel.serializer(), payload.decodeToString())
                val a = active[c.requestId] ?: return
                scope.launch {
                    val turnId = a.turnId
                    if (turnId != null) runCatching { withContext(Dispatchers.IO) { gateway()?.cancel(turnId) } }
                    a.job.cancel()
                }
            }
            WatchProtocol.PATH_PING -> scope.launch {
                refresh?.let { r -> runCatching { r() }.getOrNull()?.let { setStatus(it) } }
                sendStatus(nodeId)
            }
            else -> Log.w(TAG, "unknown path $path")
        }
    }

    /** A ChannelClient stream from the watch: header line, newline, PCM until the watch closes it. */
    fun handleAudioChannel(nodeId: String, input: InputStream) {
        val header = try {
            readHeader(input)
        } catch (e: Exception) {
            Log.w(TAG, "bad audio header: $e")
            input.close()
            return
        }
        val pcm: Flow<ByteArray> = kotlinx.coroutines.flow.flow {
            val buf = ByteArray(3200) // 100 ms at 16 kHz mono
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) emit(buf.copyOf(n))
            }
        }.onCompletion { input.close() }
        start(nodeId, header.requestId) { gw ->
            gw.audioTurn(pcm, AudioSpec(header.format, header.sampleRate, header.channels), header.session, header.locale)
        }
    }

    private fun start(nodeId: String, requestId: String, run: (TurnGateway) -> Flow<GatewayEvent>) {
        val gw = gateway()
        if (gw == null) {
            scope.launch { relayError(nodeId, requestId, "unconfigured", "the phone has no gateway configured") }
            return
        }
        active[requestId]?.job?.cancel()
        val job = scope.launch {
            _inFlight.value = active.size + 1
            try {
                run(gw)
                    .catch { e ->
                        val (code, message) = describe(e)
                        relayError(nodeId, requestId, code, message)
                        if (e is GatewayClient.GatewayException && e.status == 401) setStatus(RelayStatus("unauthorized", message))
                    }
                    .collect { event ->
                        active[requestId]?.turnId = event.turnId
                        if (event is GatewayEvent.TurnStarted && _status.value.state != "ready") setStatus(RelayStatus("ready"))
                        send(nodeId, WatchProtocol.PATH_EVENT, WatchProtocol.json.encodeToString(RelayedEvent.serializer(), RelayedEvent(requestId, GatewayEvents.encode(event))).encodeToByteArray())
                    }
            } finally {
                active.remove(requestId)
                _inFlight.value = active.size
            }
        }
        active[requestId] = Active(nodeId, job)
    }

    private suspend fun relayError(nodeId: String, requestId: String, code: String, message: String) {
        val event = GatewayEvent.Error(turnId = "", code = code, message = message, retryable = true)
        send(nodeId, WatchProtocol.PATH_EVENT, WatchProtocol.json.encodeToString(RelayedEvent.serializer(), RelayedEvent(requestId, GatewayEvents.encode(event))).encodeToByteArray())
    }

    suspend fun sendStatus(nodeId: String) {
        send(nodeId, WatchProtocol.PATH_STATUS, WatchProtocol.json.encodeToString(RelayStatus.serializer(), _status.value.copy(at = System.currentTimeMillis())).encodeToByteArray())
    }

    private fun describe(e: Throwable): Pair<String, String> = when (e) {
        is GatewayClient.GatewayException -> e.code to (e.message ?: "gateway refused")
        is IOException -> "phone_offline" to "the phone could not reach the gateway: ${e.message ?: e.javaClass.simpleName}"
        else -> "relay_failed" to (e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private const val TAG = "HermesRelay"

        fun readHeader(input: InputStream): AudioChannel {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("stream ended before the audio header")
                if (b == '\n'.code) break
                bytes.write(b)
                if (bytes.size() > AudioChannel.MAX_HEADER_BYTES) throw IOException("audio header too long")
            }
            return AudioChannel.parseHeader(bytes.toString(Charsets.UTF_8.name()))
        }
    }
}
