package dev.claudewear.wear.ui

import app.cash.turbine.test
import dev.claudewear.protocol.AnswerMessage
import dev.claudewear.protocol.AskEvent
import dev.claudewear.protocol.AskOption
import dev.claudewear.protocol.AskQuestion
import dev.claudewear.protocol.ClientMessage
import dev.claudewear.protocol.ErrorCode
import dev.claudewear.protocol.ErrorEvent
import dev.claudewear.protocol.HelloMessage
import dev.claudewear.protocol.PermissionDecision
import dev.claudewear.protocol.PermissionDecisionMessage
import dev.claudewear.protocol.PermissionEvent
import dev.claudewear.protocol.PermissionMode
import dev.claudewear.protocol.PermissionRule
import dev.claudewear.protocol.PermissionSuggestion
import dev.claudewear.protocol.PromptMessage
import dev.claudewear.protocol.REGISTRY_SESSION_ID
import dev.claudewear.protocol.Resolution
import dev.claudewear.protocol.ResolvedEvent
import dev.claudewear.protocol.ServerEvent
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.SessionSummary
import dev.claudewear.protocol.SessionsEvent
import dev.claudewear.protocol.SubscribeMessage
import dev.claudewear.protocol.TextEvent
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.protocol.TurnReason
import dev.claudewear.wear.notify.NotificationTransport
import dev.claudewear.wear.transport.Backoff
import dev.claudewear.wear.transport.ClientTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * No device, no socket: the client depends on the two transport interfaces and nothing
 * else, which is the whole reason they are interfaces.
 */
class FakeTransport : ClientTransport {
    val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val sent = mutableListOf<ClientMessage>()

    /** How many times the client has reached for a connection. */
    var connects = 0
        private set

    private var death: CompletableDeferred<Throwable?>? = null

    override fun connect(): Flow<ServerEvent> = callbackFlow {
        connects += 1
        val died = CompletableDeferred<Throwable?>()
        death = died
        val pump = launch { events.collect { trySend(it) } }
        launch {
            val cause = died.await()
            pump.cancel()
            close(cause)
        }
        awaitClose { pump.cancel() }
    }

    override suspend fun send(msg: ClientMessage) {
        sent += msg
    }

    /** End the current connection the way a walk out of Wi-Fi range does. */
    fun die(cause: Throwable? = null) {
        death?.complete(cause)
        death = null
    }

    fun greetings(): Int = sent.count { it is HelloMessage }
}

private fun summary(
    id: String,
    pending: List<String> = emptyList(),
    state: SessionState = if (pending.isEmpty()) SessionState.WORKING else SessionState.AWAITING,
    createdAt: Long = 0,
) = SessionSummary(
    sessionId = id,
    name = id,
    cwd = "/src/$id",
    state = state,
    mode = PermissionMode.DEFAULT,
    seq = 1,
    pendingRequestIds = pending,
    createdAt = createdAt,
    lastActivityAt = 0,
)

private fun sessions(seq: Long, vararg summaries: SessionSummary, roots: List<String> = emptyList()) = SessionsEvent(
    sessionId = REGISTRY_SESSION_ID,
    seq = seq,
    sessions = summaries.toList(),
    maxSessions = 5,
    projectRoots = roots,
)

private fun turn(seq: Long, state: SessionState, reason: TurnReason, sessionId: String = "s_1") = TurnEvent(
    sessionId = sessionId,
    seq = seq,
    state = state,
    reason = reason,
    requestId = if (reason == TurnReason.PERMISSION) "req_1" else null,
    sessionName = "claude-wear",
    summary = "may I run npm test?",
)

private fun ask(seq: Long, requestId: String, sessionId: String = "s_1") = AskEvent(
    sessionId = sessionId,
    seq = seq,
    requestId = requestId,
    questions = listOf(
        AskQuestion(
            question = "How should I format the output?",
            header = "Format",
            options = listOf(AskOption("Summary", "A few sentences"), AskOption("Full report", null)),
            multiSelect = false,
        ),
        AskQuestion(
            question = "Which sections?",
            header = "Sections",
            options = listOf(AskOption("Intro", null), AskOption("Findings", null)),
            multiSelect = true,
        ),
    ),
)

private fun permission(seq: Long, requestId: String, sessionId: String = "s_1") = PermissionEvent(
    sessionId = sessionId,
    seq = seq,
    requestId = requestId,
    tool = "Bash",
    input = buildJsonObject { put("command", "npm test") },
    display = "npm test",
    suggestions = listOf(
        PermissionSuggestion(
            type = "addRules",
            behavior = "allow",
            // Only a localSettings rule may be persisted from a wrist tap, so only this one
            // is allowed to become an "always allow" button.
            destination = "localSettings",
            rules = listOf(PermissionRule("Bash", "npm test:*")),
        ),
        PermissionSuggestion(
            type = "addRules",
            behavior = "allow",
            destination = "userSettings",
            rules = listOf(PermissionRule("Bash", "*")),
        ),
    ),
)

/** Records what the notification layer was told, which the vibration alone cannot show. */
private class RecordingNotifications : NotificationTransport {
    val turns = mutableListOf<TurnEvent>()
    val resolved = mutableListOf<Pair<String, String>>()

    override fun onTurn(event: TurnEvent) {
        turns += event
    }

    override fun onResolved(sessionId: String, requestId: String) {
        resolved += sessionId to requestId
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsClientTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stopTheClients() = scopes.forEach { it.cancel() }

    /**
     * Unconfined, so an emitted event has been handled by the time `emit` returns, and on
     * the *test's* scheduler, so `advanceUntilIdle` drives the client's reconnect delay.
     *
     * Neither detail is optional. A `UnconfinedTestDispatcher()` built without
     * `testScheduler` brings a scheduler of its own and strands every delay in the client;
     * and `backgroundScope` would strand them too, because `advanceUntilIdle` deliberately
     * refuses to advance time for work that is only background. Hence a scope of our own,
     * cancelled in [stopTheClients].
     */
    private fun TestScope.eagerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)).also { scopes += it }

    private fun client(
        transport: FakeTransport,
        scope: CoroutineScope,
        notifications: NotificationTransport = NotificationTransport {},
    ) = SessionsClient(
        transport = transport,
        notifications = notifications,
        scope = scope,
        deviceId = "dev_test",
        // Pinned so a reconnect test is about reconnecting rather than about waiting.
        backoff = Backoff(initialMs = 10, maxMs = 10, jitter = { 1.0 }),
    )

    @Test
    fun greetsAndSubscribesOnStart() = runTest {
        val transport = FakeTransport()
        client(transport, scope = eagerScope()).start()

        assertEquals(2, transport.sent.size)
        assertTrue(transport.sent[0] is HelloMessage)
        assertEquals(emptyMap<String, Long>(), (transport.sent[1] as SubscribeMessage).sinceSeq)
    }

    @Test
    fun rendersTheSessionListAwaitingFirst() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        vm.state.test {
            assertEquals(0, awaitItem().sessions.size)
            transport.events.emit(
                sessions(
                    1,
                    summary("s_1"),
                    summary("s_2", pending = listOf("req_1")),
                    roots = listOf("/src/claude-wear"),
                ),
            )
            val state = awaitItem()
            assertEquals(listOf("s_2", "s_1"), state.sessions.map { it.sessionId })
            assertEquals(5, state.maxSessions)
            assertEquals(listOf("/src/claude-wear"), state.projectRoots)
            assertTrue(state.connected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun handsEveryTurnToTheNotificationTransport() = runTest {
        val transport = FakeTransport()
        val seen = mutableListOf<TurnEvent>()
        val vm = client(transport, scope = eagerScope(), notifications = { seen += it })
        vm.start()

        transport.events.emit(turn(1, SessionState.WORKING, TurnReason.STARTED))
        transport.events.emit(turn(2, SessionState.AWAITING, TurnReason.PERMISSION))

        assertEquals(2, seen.size)
        assertEquals("req_1", vm.state.value.lastTurn?.requestId)
    }

    @Test
    fun keepsOneTranscriptPerChat() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        transport.events.emit(sessions(1, summary("s_1"), summary("s_2")))
        transport.events.emit(TextEvent(sessionId = "s_1", seq = 1, text = "Looking at the repo…"))
        transport.events.emit(TextEvent(sessionId = "s_2", seq = 1, text = "Reading the config…"))
        vm.prompt("s_1", "run the tests")

        assertEquals(
            listOf(
                TranscriptLine(TranscriptLine.Kind.CLAUDE, "Looking at the repo…"),
                // Echoed locally, because the bridge does not reflect prompts back.
                TranscriptLine(TranscriptLine.Kind.YOU, "run the tests"),
            ),
            vm.state.value.transcript("s_1"),
        )
        assertEquals(1, vm.state.value.transcript("s_2").size)
        assertTrue(transport.sent.any { it is PromptMessage })
    }

    @Test
    fun tracksWhoNeedsYouBetweenSnapshots() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        // Snapshots only go out when the *set* of chats changes, so a chat that starts
        // waiting between them has to be noticed from its own events.
        transport.events.emit(sessions(1, summary("s_1", createdAt = 1), summary("s_2", createdAt = 2)))
        transport.events.emit(ask(1, "req_1", sessionId = "s_2"))

        assertEquals(listOf("s_2", "s_1"), vm.state.value.sessions.map { it.sessionId })
        assertEquals(listOf("req_1"), vm.state.value.session("s_2")?.pendingRequestIds)
        assertEquals(
            TranscriptLine(TranscriptLine.Kind.WAITING, "How should I format the output?", "req_1"),
            vm.state.value.transcript("s_2").single(),
        )

        transport.events.emit(
            ResolvedEvent(
                sessionId = "s_2",
                seq = 2,
                requestId = "req_1",
                resolution = Resolution.ANSWERED,
                by = "dev_phone",
            ),
        )
        assertEquals(emptyList<String>(), vm.state.value.session("s_2")?.pendingRequestIds)
        assertEquals(listOf("s_1", "s_2"), vm.state.value.sessions.map { it.sessionId })
        // Answered somewhere else, and the transcript says so rather than going quiet.
        assertTrue(vm.state.value.transcript("s_2").last().text.contains("another device"))
    }

    @Test
    fun aReplayedRequestDoesNotSayItTwice() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        transport.events.emit(sessions(1, summary("s_1")))
        transport.events.emit(ask(1, "req_1"))
        // `subscribe` replays every outstanding request, so the same block arrives again.
        transport.events.emit(ask(2, "req_1"))

        assertEquals(1, vm.state.value.transcript("s_1").size)
        assertEquals(listOf("req_1"), vm.state.value.session("s_1")?.pendingRequestIds)
    }

    @Test
    fun forgetsAChatThatIsGone() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        transport.events.emit(sessions(1, summary("s_1")))
        transport.events.emit(TextEvent(sessionId = "s_1", seq = 1, text = "done"))
        transport.events.emit(sessions(2))

        assertNull(vm.state.value.session("s_1"))
        assertEquals(emptyList<TranscriptLine>(), vm.state.value.transcript("s_1"))
    }

    @Test
    fun separatesAConnectionProblemFromAChatProblem() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        transport.events.emit(sessions(1, summary("s_1")))
        transport.events.emit(
            ErrorEvent(
                sessionId = REGISTRY_SESSION_ID,
                seq = 2,
                code = ErrorCode.MAX_SESSIONS,
                message = "5 sessions already running",
                requestId = null,
            ),
        )
        transport.events.emit(
            ErrorEvent(
                sessionId = "s_1",
                seq = 1,
                code = ErrorCode.RUNNER_FAILED,
                message = "bypassPermissions is off on this bridge",
                requestId = null,
            ),
        )

        assertEquals("5 sessions already running", vm.state.value.error)
        assertEquals(
            TranscriptLine(TranscriptLine.Kind.PROBLEM, "bypassPermissions is off on this bridge"),
            vm.state.value.transcript("s_1").single(),
        )
    }

    @Test
    fun keepsTheWholeRequestSoACardCanShowIt() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        transport.events.emit(sessions(1, summary("s_1")))
        transport.events.emit(ask(1, "req_ask"))
        transport.events.emit(permission(2, "req_perm"))

        val question = vm.state.value.request("req_ask") as PendingRequest.Ask
        assertEquals(2, question.questions.size)
        assertEquals(listOf("Summary", "Full report"), question.questions.first().options.map { it.label })

        val perm = vm.state.value.request("req_perm") as PendingRequest.Permission
        // The actual command, not a summary of one — the card renders exactly this.
        assertEquals("npm test", perm.display)
        // Only what a wrist tap may persist. A userSettings suggestion is not an offer this
        // product makes from a 1.5" screen.
        assertEquals(listOf("npm test:*"), perm.alwaysRules)

        assertEquals(listOf("req_ask", "req_perm"), vm.state.value.pending("s_1").map { it.requestId })
    }

    @Test
    fun answersAQuestionTheWayTheSdkWantsIt() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()
        transport.events.emit(sessions(1, summary("s_1")))
        transport.events.emit(ask(1, "req_1"))

        vm.answer(
            "s_1",
            "req_1",
            mapOf(
                "How should I format the output?" to "Summary",
                // multiSelect labels arrive already joined; the SDK's answers map is
                // question text -> one string, however many chips were tapped.
                "Which sections?" to "Intro, Findings",
            ),
        )

        val sent = transport.sent.filterIsInstance<AnswerMessage>().single()
        assertEquals("req_1", sent.requestId)
        assertEquals("Summary", sent.answers?.get("How should I format the output?"))
        assertEquals("Intro, Findings", sent.answers?.get("Which sections?"))
        assertNull(sent.response)
    }

    @Test
    fun dismissesTheQuestionsAndJustTalks() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()
        transport.events.emit(sessions(1, summary("s_1")))
        transport.events.emit(ask(1, "req_1"))

        vm.respond("s_1", "req_1", "neither — look at the failing test first")

        val sent = transport.sent.filterIsInstance<AnswerMessage>().single()
        // Exactly one of the two is set: Claude gets "The user responded: …" rather than an
        // answer to a question the wearer did not want to answer.
        assertNull(sent.answers)
        assertEquals("neither — look at the failing test first", sent.response)
    }

    @Test
    fun sendsTheThreePermissionDecisions() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()
        transport.events.emit(sessions(1, summary("s_1")))

        vm.decide("s_1", "req_1", PermissionDecision.ALLOW)
        vm.decide("s_1", "req_2", PermissionDecision.ALLOW_ALWAYS)
        vm.decide("s_1", "req_3", PermissionDecision.DENY, "wrong directory")

        val sent = transport.sent.filterIsInstance<PermissionDecisionMessage>()
        assertEquals(
            listOf(PermissionDecision.ALLOW, PermissionDecision.ALLOW_ALWAYS, PermissionDecision.DENY),
            sent.map { it.decision },
        )
        // Claude sees the deny message, which is why the card offers reasons at all.
        assertEquals("wrong directory", sent.last().message)
    }

    @Test
    fun dropsTheCardWhenTheRequestIsResolvedAnywhere() = runTest {
        val transport = FakeTransport()
        val notifications = RecordingNotifications()
        val vm = client(transport, scope = eagerScope(), notifications = notifications)
        vm.start()

        transport.events.emit(sessions(1, summary("s_1")))
        transport.events.emit(permission(1, "req_1"))
        transport.events.emit(
            ResolvedEvent(
                sessionId = "s_1",
                seq = 2,
                requestId = "req_1",
                resolution = Resolution.ALLOWED,
                by = "dev_phone",
            ),
        )

        assertNull(vm.state.value.request("req_1"))
        assertEquals(emptyList<PendingRequest>(), vm.state.value.pending("s_1"))
        // And the notification goes with it: a card offering a decision that has already
        // been made is worse than no card.
        assertEquals(listOf("s_1" to "req_1"), notifications.resolved)
    }

    @Test
    fun aChatThatClosesTakesItsRequestsWithIt() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        transport.events.emit(sessions(1, summary("s_1"), summary("s_2")))
        transport.events.emit(permission(1, "req_1", sessionId = "s_1"))
        transport.events.emit(permission(1, "req_2", sessionId = "s_2"))
        transport.events.emit(sessions(2, summary("s_2")))

        assertNull(vm.state.value.request("req_1"))
        assertEquals(listOf("req_2"), vm.state.value.pending.keys.toList())
    }

    @Test
    fun aReplayedRequestAfterAReconnectDoesNotSayItTwice() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()

        transport.events.emit(sessions(1, summary("s_1")))
        transport.events.emit(ask(1, "req_1"))
        // A reconnect: the snapshot already names the blocked request, and the replay that
        // follows re-sends the request itself.
        transport.events.emit(sessions(2, summary("s_1", pending = listOf("req_1"))))
        transport.events.emit(ask(2, "req_1"))

        assertEquals(1, vm.state.value.transcript("s_1").size)
        assertEquals(listOf("req_1"), vm.state.value.pending.keys.toList())
    }

    @Test
    fun resyncsWhenTheSequenceJumps() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()
        val greeting = transport.sent.size

        transport.events.emit(TextEvent(sessionId = "s_1", seq = 1, text = "one"))
        assertEquals(greeting, transport.sent.size)

        // seq 2 was missed.
        transport.events.emit(TextEvent(sessionId = "s_1", seq = 3, text = "three"))
        val extra = transport.sent.drop(greeting)
        assertEquals(1, extra.size)
        assertEquals(mapOf("s_1" to 1L), (extra.single() as SubscribeMessage).sinceSeq)

        // One resync per gap, not one per event, until the snapshot lands.
        transport.events.emit(TextEvent(sessionId = "s_1", seq = 5, text = "five"))
        assertEquals(1, transport.sent.drop(greeting).size)
        transport.events.emit(sessions(1))
        transport.events.emit(TextEvent(sessionId = "s_1", seq = 9, text = "nine"))
        assertEquals(2, transport.sent.drop(greeting).size)
    }

    @Test
    fun reconnectsAndAsksForWhatItMissed() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()
        advanceUntilIdle()

        transport.events.emit(sessions(4, summary("s_1")))
        assertEquals(1, transport.connects)
        assertEquals(1, transport.greetings())

        transport.die(IOException("network is unreachable"))
        advanceUntilIdle()

        assertEquals(2, transport.connects)
        assertEquals(2, transport.greetings())
        // Resubscribing from what it already has is what makes walking out of range lossless.
        assertEquals(
            mapOf(REGISTRY_SESSION_ID to 4L),
            (transport.sent.last { it is SubscribeMessage } as SubscribeMessage).sinceSeq,
        )
        vm.stop()
    }

    @Test
    fun saysItIsOfflineWhileItIsOffline() = runTest {
        val transport = FakeTransport()
        val vm = client(transport, scope = eagerScope())
        vm.start()
        advanceUntilIdle()
        transport.events.emit(sessions(1))
        assertEquals(WatchState.Connection.CONNECTED, vm.state.value.connection)

        transport.die(IOException("gone"))
        assertEquals(WatchState.Connection.OFFLINE, vm.state.value.connection)
        assertEquals("gone", vm.state.value.error)

        advanceUntilIdle()
        transport.events.emit(sessions(2))
        assertEquals(WatchState.Connection.CONNECTED, vm.state.value.connection)
        // A snapshot means the bridge is answering again; the last outage stops being news.
        assertNull(vm.state.value.error)
        vm.stop()
    }
}
