package dev.claudewear.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudewear.protocol.PermissionDecision
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.TurnEvent
import dev.claudewear.wear.data.Http
import dev.claudewear.wear.data.Pairing
import dev.claudewear.wear.notify.NotificationTransport
import dev.claudewear.wear.transport.WebSocketTransport
import dev.claudewear.wear.ui.PendingRequest
import dev.claudewear.wear.ui.SessionsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end loop, on a Wear emulator against a bridge running `--fake` on the host.
 *
 * Pair, list sessions, answer the question the agent is blocked on, approve the command it
 * wants to run, then say something — the app's own transport and client, not a test harness
 * pretending to be them. The `auq-then-bash` scenario genuinely parks until each decision
 * comes back, exactly as `canUseTool` parks a real agent, so getting to the end of it is
 * proof the answers were understood and not merely sent.
 *
 * The cards themselves are driven by `CardsTest` on the JVM and photographed by
 * `ScreenTourTest` on this same emulator; what this adds is the whole path over a real
 * socket.
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
    fun pairsAnswersAQuestionApprovesACommandAndSpeaks() = runBlocking {
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

            // The question card's job, over the wire: the answers map is keyed by the
            // question text and valued by the label that was tapped.
            val question = withTimeout(TIMEOUT_MS) {
                client.state.first { it.pending.values.any { request -> request is PendingRequest.Ask } }
                    .pending.values.filterIsInstance<PendingRequest.Ask>().first()
            }
            val asked = question.questions.first()
            client.answer(
                session.sessionId,
                question.requestId,
                mapOf(asked.question to asked.options.first().label),
            )

            // The scenario only reaches its Bash step once the answer is accepted, so a
            // permission arriving at all is the answer having been understood.
            val permission = withTimeout(TIMEOUT_MS) {
                client.state.first { it.pending.values.any { request -> request is PendingRequest.Permission } }
                    .pending.values.filterIsInstance<PendingRequest.Permission>().first()
            }
            // The card renders this verbatim; approving what you cannot see is the failure
            // mode the whole product designs against.
            assertTrue("a permission should say what it wants to run", permission.display.isNotEmpty())
            client.decide(session.sessionId, permission.requestId, PermissionDecision.ALLOW)

            // Both decisions landed, so the agent ran to the end of its script and nothing
            // is blocked any more.
            withTimeout(TIMEOUT_MS) {
                client.state.first { state ->
                    state.session(session.sessionId)?.state == SessionState.IDLE && state.pending.isEmpty()
                }
            }

            // A prompt from the chat screen shows up in the chat immediately, because the
            // bridge does not reflect prompts back and a chat that swallows what you just
            // said looks broken. That echo is local, though, so it is not evidence the
            // frame ever left the watch — for that, ask the bridge what it received.
            client.prompt(session.sessionId, PROMPT)
            val said = withTimeout(TIMEOUT_MS) {
                client.state.first { it.transcript(session.sessionId).any { line -> line.text == PROMPT } }
            }
            assertTrue(said.transcript(session.sessionId).isNotEmpty())
            withTimeout(TIMEOUT_MS) {
                for (type in listOf("answer", "permission", "prompt")) {
                    while (!bridgeReceived(type)) delay(200)
                }
            }
        } finally {
            client.stop()
            scope.cancel()
        }
    }

    /**
     * What `--inbox` recorded. `scripts/e2e.sh` asserts against the same endpoint once the
     * run is over; the test polls it so it does not tear its scope down — and with it the
     * coroutine doing the sending — in the window between the local echo and the write.
     */
    private suspend fun bridgeReceived(type: String): Boolean {
        val body = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("${bridgeUrl.trimEnd('/')}/debug/inbox").build()
            Http.client().newCall(request).execute().use { response ->
                // Distinguished from "not yet", which is the whole point of polling: an
                // inbox that is off would otherwise look like a prompt that never arrived.
                check(response.code != 404) { "the bridge's inbox is off; scripts/e2e.sh starts it with --inbox" }
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } ?: return false

        val entries = JSONObject(body).getJSONArray("entries")
        return (0 until entries.length())
            .map { entries.getJSONObject(it) }
            .any { it.optString("direction") == "in" && it.optString("type") == type }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val PROMPT = "and now the linter"
    }
}
