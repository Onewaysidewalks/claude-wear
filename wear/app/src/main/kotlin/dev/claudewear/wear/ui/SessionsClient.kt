package dev.claudewear.wear.ui

import android.util.Log
import dev.claudewear.protocol.AnswerMessage
import dev.claudewear.protocol.AskEvent
import dev.claudewear.protocol.ClientMessage
import dev.claudewear.protocol.DoneEvent
import dev.claudewear.protocol.ErrorEvent
import dev.claudewear.protocol.HelloMessage
import dev.claudewear.protocol.InterruptMessage
import dev.claudewear.protocol.NewSessionMessage
import dev.claudewear.protocol.PROTOCOL_VERSION
import dev.claudewear.protocol.PermissionDecision
import dev.claudewear.protocol.PermissionDecisionMessage
import dev.claudewear.protocol.PermissionEvent
import dev.claudewear.protocol.PermissionMode
import dev.claudewear.protocol.PromptMessage
import dev.claudewear.protocol.REGISTRY_SESSION_ID
import dev.claudewear.protocol.ResolvedEvent
import dev.claudewear.protocol.ServerEvent
import dev.claudewear.protocol.SessionsEvent
import dev.claudewear.protocol.SetModeMessage
import dev.claudewear.protocol.SubscribeMessage
import dev.claudewear.protocol.TextEvent
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.wear.notify.NotificationTransport
import dev.claudewear.wear.transport.Backoff
import dev.claudewear.wear.transport.ClientTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "SessionsClient"
private const val TRANSCRIPT_LIMIT = 50

/**
 * The watch's half of the protocol: holds the socket open, keeps the session list honest,
 * and hands every turn to the notification transport.
 *
 * Depends on the two transport interfaces and never on OkHttp or a notification manager,
 * so the whole state machine is testable with no device. [ConnectionService] owns the
 * instance; the screens only observe [state] and call the send methods.
 */
class SessionsClient(
    private val transport: ClientTransport,
    private val notifications: NotificationTransport,
    private val scope: CoroutineScope,
    private val deviceId: String = "unknown",
    private val deviceName: String = "watch",
    private val clientVersion: String = "0.1.0",
    private val backoff: Backoff = Backoff(),
) {

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    /** Highest seq seen per session, which is how a gap is spotted after a bad reconnect. */
    private val seqBySession = mutableMapOf<String, Long>()
    private var resyncing = false
    private var job: Job? = null

    // --- lifecycle ------------------------------------------------------------

    /**
     * Connect, and keep connecting. A watch walks out of Wi-Fi range as a matter of
     * routine, so a single attempt is not a connection strategy — the loop is the feature,
     * and `subscribe` on the way back in is what makes it lossless.
     */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    connectOnce()
                    Log.i(TAG, "bridge closed the socket")
                    _state.update { it.copy(connection = WatchState.Connection.OFFLINE) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w(TAG, "connection ended", failure)
                    _state.update {
                        it.copy(
                            connection = WatchState.Connection.OFFLINE,
                            error = failure.message ?: "the bridge went away",
                        )
                    }
                }
                delay(backoff.nextMs())
                _state.update { it.copy(connection = WatchState.Connection.CONNECTING) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(connection = WatchState.Connection.OFFLINE) }
    }

    private suspend fun connectOnce() = coroutineScope {
        // `send` parks until the socket is open, so the greeting can be queued up front and
        // will go out on this connection — including on every reconnection after the first.
        val greeting = launch {
            transport.send(
                HelloMessage(
                    protocolVersion = PROTOCOL_VERSION,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    clientVersion = clientVersion,
                ),
            )
            transport.send(SubscribeMessage(sinceSeq = seqBySession.toMap()))
        }
        try {
            transport.connect().collect { event ->
                // A connection that delivered something is a connection worth trusting;
                // one that opens and is immediately rejected is not, and keeps backing off.
                backoff.reset()
                onEvent(event)
            }
        } finally {
            greeting.cancel()
        }
    }

    // --- what the screens can do ----------------------------------------------

    fun newSession(cwd: String, name: String? = null) = send(NewSessionMessage(cwd = cwd, name = name))

    fun prompt(sessionId: String, text: String) {
        // Echoed locally because the bridge does not reflect prompts back, and a chat that
        // swallows what you just said looks broken long before the agent answers.
        _state.update { it.plusLine(sessionId, TranscriptLine.Kind.YOU, text) }
        send(PromptMessage(sessionId = sessionId, text = text))
    }

    /**
     * Answer an AskUserQuestion by picking options: question text -> the chosen label, with a
     * multiSelect question's labels joined. Dictated free text goes in as the value, exactly
     * as Claude Desktop's "Other" does — Claude gets the answer, not the word "Other".
     */
    fun answer(sessionId: String, requestId: String, answers: Map<String, String>) =
        send(AnswerMessage(sessionId = sessionId, requestId = requestId, answers = answers, response = null))

    /**
     * Dismiss the questions and just talk. Claude receives "The user responded: …", which is
     * the escape hatch for a question whose options are all wrong.
     */
    fun respond(sessionId: String, requestId: String, response: String) =
        send(AnswerMessage(sessionId = sessionId, requestId = requestId, answers = null, response = response))

    /**
     * Allow, allow-always or deny a tool call. A deny [message] is visible to Claude, so the
     * agent adapts rather than retrying blindly — which is why the card offers reasons.
     */
    fun decide(sessionId: String, requestId: String, decision: PermissionDecision, message: String? = null) =
        send(
            PermissionDecisionMessage(
                sessionId = sessionId,
                requestId = requestId,
                decision = decision,
                message = message,
            ),
        )

    fun interrupt(sessionId: String) = send(InterruptMessage(sessionId = sessionId))

    fun setMode(sessionId: String, mode: PermissionMode) = send(SetModeMessage(sessionId = sessionId, mode = mode))

    private fun send(message: ClientMessage) {
        scope.launch {
            runCatching { transport.send(message) }
                .onFailure { _state.update { state -> state.copy(error = it.message) } }
        }
    }

    // --- events ----------------------------------------------------------------

    private fun onEvent(event: ServerEvent) {
        noteSeq(event)
        // Side effects before the state edit, never inside it: `update` re-runs its lambda
        // when it loses a race, and a second buzz for one turn is a bug you cannot unsee.
        if (event is TurnEvent) notifications.onTurn(event)
        // The card may have been answered from the CLI, the phone, or another watch; a
        // notification for a decision that has already been made is worse than none.
        if (event is ResolvedEvent) notifications.onResolved(event.sessionId, event.requestId)
        if (event is SessionsEvent) resyncing = false

        _state.update { current ->
            val next = when (event) {
                is SessionsEvent -> {
                    val live = event.sessions.map(::SessionView)
                    val known = live.map { it.sessionId }.toSet()
                    current.copy(
                        sessions = live.awaitingFirst(),
                        maxSessions = event.maxSessions.toInt(),
                        projectRoots = event.projectRoots,
                        // A snapshot means the bridge is answering again, so whatever went
                        // wrong last time is over.
                        error = null,
                        // A closed chat keeps nothing on the wrist.
                        transcripts = current.transcripts.filterKeys { it in known },
                        pending = current.pending.filterValues { it.sessionId in known },
                    )
                }

                is TurnEvent -> current
                    .withSession(event.sessionId) { it.copy(state = event.state) }
                    .copy(lastTurn = event)

                is AskEvent -> current.waitingOnYou(
                    PendingRequest.Ask(
                        sessionId = event.sessionId,
                        requestId = event.requestId,
                        questions = event.questions,
                    ),
                )

                is PermissionEvent -> current.waitingOnYou(
                    PendingRequest.Permission(
                        sessionId = event.sessionId,
                        requestId = event.requestId,
                        tool = event.tool,
                        display = event.display,
                        suggestions = event.suggestions,
                    ),
                )

                // Possibly answered from the CLI, the phone, or another watch. Saying so
                // beats a card that silently stops mattering.
                is ResolvedEvent -> current
                    .withSession(event.sessionId) { it.copy(pendingRequestIds = it.pendingRequestIds - event.requestId) }
                    .copy(pending = current.pending - event.requestId)
                    .let { after ->
                        if (event.by == null || event.by == deviceId) {
                            after
                        } else {
                            after.plusLine(
                                event.sessionId,
                                TranscriptLine.Kind.RESULT,
                                "${event.resolution.name.lowercase()} on another device",
                            )
                        }
                    }

                is TextEvent -> current.plusLine(event.sessionId, TranscriptLine.Kind.CLAUDE, event.text)

                is DoneEvent -> current.plusLine(
                    event.sessionId,
                    TranscriptLine.Kind.RESULT,
                    event.result ?: "(no result)",
                )

                is ErrorEvent ->
                    if (event.sessionId == REGISTRY_SESSION_ID) {
                        current.copy(error = event.message)
                    } else {
                        current.plusLine(event.sessionId, TranscriptLine.Kind.PROBLEM, event.message)
                    }
            }
            // An event in hand is the only proof of a live socket worth having.
            next.copy(connection = WatchState.Connection.CONNECTED)
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

    // --- state edits -------------------------------------------------------------

    /** No-op for a chat no snapshot has named yet; the snapshot that follows carries the truth. */
    private fun WatchState.withSession(sessionId: String, edit: (SessionView) -> SessionView): WatchState {
        if (sessions.none { it.sessionId == sessionId }) return this
        return copy(sessions = sessions.map { if (it.sessionId == sessionId) edit(it) else it }.awaitingFirst())
    }

    /**
     * A replayed `subscribe` re-sends every outstanding request, so the same block can
     * arrive twice. The request map and the pending set both key on requestId; the
     * transcript line only lands the first time.
     */
    private fun WatchState.waitingOnYou(request: PendingRequest): WatchState {
        // The snapshot that lands on a reconnect already names every blocked request, and the
        // replay that follows it re-sends the requests themselves. Either one is proof this
        // is not news.
        val alreadyKnown = request.requestId in pending ||
            session(request.sessionId)?.pendingRequestIds?.contains(request.requestId) == true
        val waiting = withSession(request.sessionId) {
            it.copy(pendingRequestIds = (it.pendingRequestIds + request.requestId).distinct())
        }.copy(pending = pending + (request.requestId to request))
        return if (alreadyKnown) {
            waiting
        } else {
            waiting.plusLine(request.sessionId, TranscriptLine.Kind.WAITING, request.summary, request.requestId)
        }
    }

    private fun WatchState.plusLine(
        sessionId: String,
        kind: TranscriptLine.Kind,
        text: String,
        requestId: String? = null,
    ): WatchState {
        val lines = (transcripts[sessionId].orEmpty() + TranscriptLine(kind, text, requestId)).takeLast(TRANSCRIPT_LIMIT)
        return copy(transcripts = transcripts + (sessionId to lines))
    }
}
