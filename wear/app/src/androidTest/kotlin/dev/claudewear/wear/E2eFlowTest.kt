package dev.claudewear.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.wear.data.Pairing
import dev.claudewear.wear.notify.NotificationTransport
import dev.claudewear.wear.transport.WebSocketTransport
import dev.claudewear.wear.ui.SessionsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end loop, on a Wear emulator against a bridge running `--fake` on the host.
 *
 * Pair, list sessions, open a chat, say something, receive a turn — the app's own transport
 * and client, not a test harness pretending to be them. Answering an AUQ and approving a
 * permission join this once there are cards to drive them, in M3.
 *
 * scripts/e2e.sh supplies bridgeUrl, pairCode and cwd, and asserts against the bridge's
 * recorded inbox afterwards.
 */
@RunWith(AndroidJUnit4::class)
class E2eFlowTest {

    private val args = InstrumentationRegistry.getArguments()
    private val bridgeUrl: String = args.getString("bridgeUrl") ?: "http://10.0.2.2:8787"
    private val pairCode: String = requireNotNull(args.getString("pairCode")) {
        "pass -e pairCode <code>; scripts/e2e.sh reads it from the bridge's output"
    }
    private val cwd: String = requireNotNull(args.getString("cwd")) {
        "pass -e cwd <path>; it must exist on the bridge host, not on the emulator"
    }

    @Test
    fun pairsListsSessionsAndReceivesATurn() = runBlocking {
        val pairing = withTimeout(TIMEOUT_MS) { Pairing.pair(bridgeUrl, pairCode, "e2e watch") }
        assertTrue("expected a device id", pairing.deviceId.startsWith("dev_"))
        assertTrue("expected a token", pairing.token.isNotEmpty())

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val turns = mutableListOf<TurnEvent>()
        val client = SessionsClient(
            transport = WebSocketTransport(bridgeUrl, pairing.token),
            notifications = NotificationTransport { turns += it },
            scope = scope,
            deviceId = pairing.deviceId,
            deviceName = "e2e watch",
        )

        try {
            client.start()

            // Connected: the bridge answered `hello` with a snapshot, and the snapshot says
            // which directories the New chat screen may offer.
            val connected = withTimeout(TIMEOUT_MS) { client.state.first { it.connected } }
            assertEquals(listOf(cwd), connected.projectRoots)

            client.newSession(cwd, "e2e")

            val listed = withTimeout(TIMEOUT_MS) { client.state.first { it.sessions.isNotEmpty() } }
            val session = listed.sessions.single()
            assertEquals("e2e", session.name)
            assertEquals(cwd, session.cwd)

            // And it is your turn: the fake agent finished, or it is blocked on you.
            val turned = withTimeout(TIMEOUT_MS) {
                client.state.first { state ->
                    val live = state.session(session.sessionId)
                    live?.state == SessionState.IDLE || live?.awaiting == true
                }
            }
            val turn = requireNotNull(turned.lastTurn)
            assertEquals("e2e", turn.sessionName)
            assertTrue("a turn should say something", turn.summary.isNotEmpty())
            assertTrue("the notification transport should have been told", turns.isNotEmpty())

            // A prompt from the chat screen lands in this chat's transcript, and in the
            // bridge's inbox, which is what scripts/e2e.sh checks afterwards.
            client.prompt(session.sessionId, "and now the linter")
            val said = withTimeout(TIMEOUT_MS) {
                client.state.first { it.transcript(session.sessionId).any { line -> line.text == "and now the linter" } }
            }
            assertTrue(said.transcript(session.sessionId).isNotEmpty())
        } finally {
            client.stop()
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
