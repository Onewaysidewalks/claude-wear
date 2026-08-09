package dev.claudewear.wear.ui

import app.cash.turbine.test
import dev.claudewear.protocol.ClientMessage
import dev.claudewear.protocol.HelloMessage
import dev.claudewear.protocol.PermissionMode
import dev.claudewear.protocol.ServerEvent
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.SessionSummary
import dev.claudewear.protocol.SessionsEvent
import dev.claudewear.protocol.SubscribeMessage
import dev.claudewear.protocol.TextEvent
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.protocol.TurnReason
import dev.claudewear.wear.notify.NotificationTransport
import dev.claudewear.wear.transport.ClientTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No device, no socket: the ViewModel depends on the two transport interfaces and nothing else. */
class FakeTransport : ClientTransport {
    val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val sent = mutableListOf<ClientMessage>()

    override fun connect(): Flow<ServerEvent> = events

    override suspend fun send(msg: ClientMessage) {
        sent += msg
    }
}

private fun summary(id: String, pending: List<String> = emptyList()) = SessionSummary(
    sessionId = id,
    name = id,
    cwd = "/src/$id",
    state = if (pending.isEmpty()) SessionState.WORKING else SessionState.AWAITING,
    mode = PermissionMode.DEFAULT,
    seq = 1,
    pendingRequestIds = pending,
    createdAt = 0,
    lastActivityAt = 0,
)

private fun sessions(seq: Long, vararg summaries: SessionSummary) = SessionsEvent(
    sessionId = "@registry",
    seq = seq,
    sessions = summaries.toList(),
    maxSessions = 5,
)

private fun turn(seq: Long, state: SessionState, reason: TurnReason) = TurnEvent(
    sessionId = "s_1",
    seq = seq,
    state = state,
    reason = reason,
    requestId = if (reason == TurnReason.PERMISSION) "req_1" else null,
    sessionName = "claude-wear",
    summary = "may I run npm test?",
)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private fun viewModel(
        transport: FakeTransport,
        notifications: NotificationTransport = NotificationTransport {},
        scope: kotlinx.coroutines.CoroutineScope,
    ) = SessionsViewModel(transport, notifications, scope, deviceId = "dev_test")

    @Test
    fun greetsAndSubscribesOnStart() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        viewModel(transport, scope = backgroundScope).start()

        assertEquals(2, transport.sent.size)
        assertTrue(transport.sent[0] is HelloMessage)
        val subscribe = transport.sent[1] as SubscribeMessage
        assertEquals(emptyMap<String, Long>(), subscribe.sinceSeq)
    }

    @Test
    fun rendersTheSessionList() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val vm = viewModel(transport, scope = backgroundScope)
        vm.start()

        vm.state.test {
            assertEquals(0, awaitItem().sessions.size)
            transport.events.emit(sessions(1, summary("s_1"), summary("s_2", listOf("req_1"))))
            val state = awaitItem()
            assertEquals(2, state.sessions.size)
            assertEquals(5, state.maxSessions)
            assertEquals(listOf("s_2"), state.awaiting.map { it.sessionId })
            assertTrue(state.connected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun handsEveryTurnToTheNotificationTransport() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val seen = mutableListOf<TurnEvent>()
        val vm = viewModel(transport, notifications = { seen += it }, scope = backgroundScope)
        vm.start()

        transport.events.emit(turn(1, SessionState.WORKING, TurnReason.STARTED))
        transport.events.emit(turn(2, SessionState.AWAITING, TurnReason.PERMISSION))

        assertEquals(2, seen.size)
        assertEquals("req_1", vm.state.value.lastTurn?.requestId)
    }

    @Test
    fun keepsACondensedTranscript() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val vm = viewModel(transport, scope = backgroundScope)
        vm.start()

        transport.events.emit(TextEvent(sessionId = "s_1", seq = 1, text = "Looking at the repo…"))
        assertEquals(listOf("Looking at the repo…"), vm.state.value.transcript)
    }

    @Test
    fun resyncsWhenTheSequenceJumps() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val vm = viewModel(transport, scope = backgroundScope)
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

        assertEquals(4, vm.state.value.transcript.size)
    }
}
