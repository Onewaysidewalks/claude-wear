package dev.claudewear.phone.relay

import dev.claudewear.phone.data.GatewayClient
import dev.claudewear.phone.data.TurnGateway
import dev.claudewear.shared.ApprovalAnswer
import dev.claudewear.shared.AudioChannel
import dev.claudewear.shared.AudioSpec
import dev.claudewear.shared.GatewayEvent
import dev.claudewear.shared.RelayedEvent
import dev.claudewear.shared.TextTurn
import dev.claudewear.shared.WatchProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayTest {
    private class FakeGateway : TurnGateway {
        val texts = mutableListOf<String>()
        val approvals = mutableListOf<Triple<String, String, String>>()
        val cancels = mutableListOf<String>()
        var audioBytes = 0
        var audioSpec: AudioSpec? = null
        var fail: Throwable? = null

        private fun script(turnId: String, answer: String): Flow<GatewayEvent> = flow {
            fail?.let { throw it }
            emit(GatewayEvent.TurnStarted(turnId, 1, "x", "watch", "text"))
            emit(GatewayEvent.ApprovalRequired(turnId, 2, "x", "a1", "do it", "", "high", "later"))
            emit(GatewayEvent.OutputDone(turnId, 3, "x", answer))
            emit(GatewayEvent.TurnCompleted(turnId, 4, "x", "ok"))
        }

        override fun textTurn(text: String, session: String, locale: String, reset: Boolean): Flow<GatewayEvent> {
            texts += "$session:$text"
            return script("t-text", "You said: $text")
        }

        override fun audioTurn(pcm: Flow<ByteArray>, spec: AudioSpec, session: String, locale: String): Flow<GatewayEvent> = flow {
            audioSpec = spec
            pcm.collect { audioBytes += it.size }
            emitAll(script("t-audio", "heard $audioBytes bytes"))
        }

        private suspend fun kotlinx.coroutines.flow.FlowCollector<GatewayEvent>.emitAll(f: Flow<GatewayEvent>) = f.collect { emit(it) }

        override fun approve(turnId: String, approvalId: String, decision: String) {
            approvals += Triple(turnId, approvalId, decision)
        }

        override fun cancel(turnId: String) {
            cancels += turnId
        }
    }

    private val gateway = FakeGateway()
    private val sent = LinkedBlockingQueue<Triple<String, String, String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var configured = true
    private val relay = Relay(
        gateway = { if (configured) gateway else null },
        send = { node, path, payload -> sent.put(Triple(node, path, payload.decodeToString())) },
        scope = scope,
    )

    private fun drainEvents(n: Int): List<RelayedEvent> = (1..n).map {
        val (node, path, payload) = sent.poll(5, TimeUnit.SECONDS) ?: error("no message $it")
        assertEquals("watch-1", node)
        assertEquals(WatchProtocol.PATH_EVENT, path)
        WatchProtocol.json.decodeFromString(RelayedEvent.serializer(), payload)
    }

    @Test
    fun `text turn is relayed event for event with the request id`() {
        val turn = TextTurn(requestId = "r1", text = "hello", session = "watch", locale = "en-US")
        relay.handleMessage("watch-1", WatchProtocol.PATH_TURN_TEXT, WatchProtocol.json.encodeToString(TextTurn.serializer(), turn).encodeToByteArray())
        val events = drainEvents(4)
        assertTrue(events.all { it.requestId == "r1" })
        assertEquals(listOf("watch:hello"), gateway.texts)
        assertEquals("You said: hello", (events[2].event() as GatewayEvent.OutputDone).text)
        assertTrue(events[1].eventJson.contains("\"type\":\"approval.required\""))
    }

    @Test
    fun `audio channel header then pcm reaches the gateway as one utterance`() {
        val header = AudioChannel(requestId = "r2", locale = "en-GB", sampleRate = 16000).headerBytes()
        val pcm = ByteArray(7000) { 3 }
        relay.handleAudioChannel("watch-1", ByteArrayInputStream(header + pcm))
        val events = drainEvents(4)
        assertEquals(7000, gateway.audioBytes)
        assertEquals(AudioSpec("pcm_s16le", 16000, 1), gateway.audioSpec)
        assertEquals("heard 7000 bytes", (events[2].event() as GatewayEvent.OutputDone).text)
    }

    @Test
    fun `approval and cancel are forwarded`() = runBlocking {
        relay.handleMessage("watch-1", WatchProtocol.PATH_APPROVAL, WatchProtocol.json.encodeToString(ApprovalAnswer.serializer(), ApprovalAnswer("r1", "t-text", "a1", "deny")).encodeToByteArray())
        withTimeout(5000) { while (gateway.approvals.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(Triple("t-text", "a1", "deny"), gateway.approvals.single())
    }

    @Test
    fun `an unconfigured phone answers with an error event instead of silence`() {
        configured = false
        relay.handleMessage("watch-1", WatchProtocol.PATH_TURN_TEXT, """{"request_id":"r3","text":"hi"}""".encodeToByteArray())
        val (e) = drainEvents(1)
        val err = e.event() as GatewayEvent.Error
        assertEquals("unconfigured", err.code)
        assertEquals("r3", e.requestId)
    }

    @Test
    fun `gateway refusal is relayed as an error and 401 flips the status`() {
        gateway.fail = GatewayClient.GatewayException(401, "unauthorized", "unknown token")
        relay.handleMessage("watch-1", WatchProtocol.PATH_TURN_TEXT, """{"request_id":"r4","text":"hi"}""".encodeToByteArray())
        val (e) = drainEvents(1)
        assertEquals("unauthorized", (e.event() as GatewayEvent.Error).code)
        runBlocking { withTimeout(5000) { while (relay.status.value.state != "unauthorized") kotlinx.coroutines.delay(10) } }
    }

    @Test
    fun `ping answers with status`() {
        relay.handleMessage("watch-1", WatchProtocol.PATH_PING, ByteArray(0))
        val (_, path, payload) = sent.poll(5, TimeUnit.SECONDS)!!
        assertEquals(WatchProtocol.PATH_STATUS, path)
        assertTrue(payload.contains("\"state\":\"unconfigured\""))
    }

    @Test
    fun `header reader stops at the newline and rejects garbage`() {
        val h = Relay.readHeader(ByteArrayInputStream("""{"request_id":"x","sample_rate":8000}""".toByteArray() + "\n".toByteArray() + ByteArray(10)))
        assertEquals(8000, h.sampleRate)
        assertTrue(runCatching { Relay.readHeader(ByteArrayInputStream(ByteArray(5000) { 65 })) }.isFailure)
    }
}
