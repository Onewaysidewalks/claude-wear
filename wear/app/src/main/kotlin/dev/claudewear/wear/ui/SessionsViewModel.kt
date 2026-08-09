package dev.claudewear.wear.ui

import android.util.Log
import dev.claudewear.protocol.AskEvent
import dev.claudewear.protocol.ClientMessage
import dev.claudewear.protocol.DoneEvent
import dev.claudewear.protocol.ErrorEvent
import dev.claudewear.protocol.HelloMessage
import dev.claudewear.protocol.NewSessionMessage
import dev.claudewear.protocol.PROTOCOL_VERSION
import dev.claudewear.protocol.PermissionEvent
import dev.claudewear.protocol.PromptMessage
import dev.claudewear.protocol.ResolvedEvent
import dev.claudewear.protocol.ServerEvent
import dev.claudewear.protocol.SessionsEvent
import dev.claudewear.protocol.SubscribeMessage
import dev.claudewear.protocol.TextEvent
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.wear.notify.NotificationTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import dev.claudewear.wear.transport.ClientTransport

private const val TAG = "SessionsViewModel"
private const val TRANSCRIPT_LIMIT = 50

/**
 * Holds the socket open, keeps the session list, and hands every turn to the notification
 * transport. Depends on the two interfaces and never on OkHttp or a notification manager,
 * so it is testable with a fake transport and no device.
 *
 * M0 renders questions and permissions as transcript lines. The cards are M3.
 */
class SessionsViewModel(
    private val transport: ClientTransport,
    private val notifications: NotificationTransport,
    private val scope: CoroutineScope,
    private val deviceId: String = "unknown",
    private val deviceName: String = "watch",
    private val clientVersion: String = "0.1.0",
) {

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    /** Highest seq seen per session, which is how a gap is spotted after a bad reconnect. */
    private val seqBySession = mutableMapOf<String, Long>()
    private var resyncing = false
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            transport.connect()
                .catch { t ->
                    Log.w(TAG, "connection ended", t)
                    _state.value = _state.value.copy(connected = false, error = t.message)
                }
                .collect { event -> onEvent(event) }
        }
        scope.launch {
            transport.send(
                HelloMessage(
                    protocolVersion = PROTOCOL_VERSION,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    clientVersion = clientVersion,
                ),
            )
            resubscribe()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun newSession(cwd: String, name: String? = null) = send(NewSessionMessage(cwd = cwd, name = name))

    fun prompt(sessionId: String, text: String) = send(PromptMessage(sessionId = sessionId, text = text))

    private fun send(message: ClientMessage) {
        scope.launch {
            runCatching { transport.send(message) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    private suspend fun resubscribe() {
        transport.send(SubscribeMessage(sinceSeq = seqBySession.toMap()))
    }

    private fun onEvent(event: ServerEvent) {
        noteSeq(event)
        val current = _state.value
        _state.value = when (event) {
            is SessionsEvent -> {
                resyncing = false
                current.copy(connected = true, sessions = event.sessions, maxSessions = event.maxSessions.toInt())
            }
            is TurnEvent -> {
                notifications.onTurn(event)
                current.copy(connected = true, lastTurn = event)
            }
            is TextEvent -> current.plusLine(event.text)
            is DoneEvent -> current.plusLine(event.result ?: "(no result)")
            is AskEvent -> current.plusLine("waiting on you: ${event.questions.firstOrNull()?.question ?: "a question"}")
            is PermissionEvent -> current.plusLine("waiting on you: ${event.tool} — ${event.display}")
            is ResolvedEvent -> current
            is ErrorEvent -> current.copy(error = event.message)
        }
    }

    /**
     * Every server event carries a per-session monotonic seq. A forward jump means frames
     * were missed, and the fix is the same one a reconnect uses: ask for a full resync.
     */
    private fun noteSeq(event: ServerEvent) {
        val previous = seqBySession[event.sessionId]
        if (previous != null && event.seq > previous + 1 && !resyncing) {
            Log.w(TAG, "gap on ${event.sessionId}: $previous -> ${event.seq}; resyncing")
            resyncing = true
            // The last seq we are sure about is the one before the gap, not the one that
            // just arrived — so ask from there.
            val known = seqBySession.toMap()
            scope.launch { runCatching { transport.send(SubscribeMessage(sinceSeq = known)) } }
        }
        if (previous == null || event.seq > previous) seqBySession[event.sessionId] = event.seq
    }

    private fun WatchState.plusLine(line: String): WatchState =
        copy(connected = true, transcript = (transcript + line).takeLast(TRANSCRIPT_LIMIT))
}
