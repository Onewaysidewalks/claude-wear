package dev.claudewear.phone

import dev.claudewear.phone.data.GatewayClient
import dev.claudewear.phone.data.GatewayConfig
import dev.claudewear.phone.relay.Relay
import dev.claudewear.shared.ApprovalAnswer
import dev.claudewear.shared.AudioChannel
import dev.claudewear.shared.AudioSpec
import dev.claudewear.shared.GatewayEvent
import dev.claudewear.shared.RelayedEvent
import dev.claudewear.shared.TextTurn
import dev.claudewear.shared.WatchProtocol
import dev.claudewear.shared.typeName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The real Python gateway (fake brain, fake STT) on one side, the real Kotlin client and relay
 * on the other. This is the cross-language check of the SSE framing, the WebSocket control
 * frames, approvals, and the relay's byte stream to the watch. It is skipped unless
 * scripts/loopback.sh started a gateway and exported its address and a token.
 */
class LoopbackTest {
    private val url = System.getProperty("loopback.url").orEmpty()
    private val token = System.getProperty("loopback.token").orEmpty()
    private lateinit var client: GatewayClient

    @Before
    fun up() {
        assumeTrue("no loopback gateway (run scripts/loopback.sh)", url.isNotEmpty() && token.isNotEmpty())
        client = GatewayClient({ GatewayConfig(url, token) })
    }

    @Test
    fun `health and pairing`() {
        val h = client.health()
        assertTrue(h.ok)
        assertEquals("fake", h.brainKind)
        assertTrue(h.brainReachable)
        assertEquals("fake", h.stt)
        assertEquals("loopback", client.whoami())
    }

    @Test
    fun `text turn over SSE`() = runBlocking {
        val events = client.textTurn("echo hello from kotlin", session = "loopback-text").toList()
        assertEquals("turn.started", events.first().typeName)
        assertEquals("turn.completed", events.last().typeName)
        assertEquals("hello from kotlin", events.filterIsInstance<GatewayEvent.OutputDelta>().joinToString("") { it.text })
        assertEquals("hello from kotlin", events.filterIsInstance<GatewayEvent.OutputDone>().single().text)
        assertEquals("ok", (events.last() as GatewayEvent.TurnCompleted).status)
        assertEquals(events.map { it.seq }, events.map { it.seq }.sorted())
    }

    @Test
    fun `audio turn over the WebSocket`() = runBlocking {
        val pcm = flow { repeat(12) { emit(ByteArray(3200) { 7 }) } } // 1.2 s at 16 kHz
        val events = client.audioTurn(pcm, AudioSpec(sampleRate = 16000), session = "loopback-audio", locale = "en-GB").toList()
        val names = events.map { it.typeName }
        assertEquals(listOf("turn.started", "input.transcript"), names.take(2))
        assertEquals("turn.completed", names.last())
        assertEquals("fake transcript of 1200 ms at 16000 Hz in en-GB", events.filterIsInstance<GatewayEvent.InputTranscript>().single().text)
        assertEquals("You said: fake transcript of 1200 ms at 16000 Hz in en-GB", events.filterIsInstance<GatewayEvent.OutputDone>().single().text)
    }

    @Test
    fun `approval round trip, denied, high risk`() = runBlocking {
        val seen = mutableListOf<GatewayEvent>()
        client.textTurn("danger wipe it", session = "loopback-approval").collect { e ->
            seen += e
            if (e is GatewayEvent.ApprovalRequired) {
                assertTrue(e.highRisk)
                client.approve(e.turnId, e.approvalId, "deny")
            }
        }
        assertEquals("deny", seen.filterIsInstance<GatewayEvent.ApprovalResolved>().single().decision)
        assertEquals("Okay, I won't.", seen.filterIsInstance<GatewayEvent.OutputDone>().single().text)
    }

    @Test
    fun `gateway refusals reach the client as typed errors`() = runBlocking {
        val bad = GatewayClient({ GatewayConfig(url, "hg1_not_a_token") })
        val e = runCatching { bad.textTurn("hi").toList() }.exceptionOrNull() as GatewayClient.GatewayException
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.code)
    }

    @Test
    fun `the relay forwards a watch text turn and an audio channel through the real gateway`() {
        val sent = LinkedBlockingQueue<Pair<String, ByteArray>>()
        val relay = Relay(
            gateway = { client },
            send = { _, path, payload -> sent.put(path to payload) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        relay.handleMessage("watch", WatchProtocol.PATH_TURN_TEXT, WatchProtocol.json.encodeToString(TextTurn.serializer(), TextTurn("r-text", "echo via relay", session = "loopback-relay")).encodeToByteArray())
        val text = drain(sent, "r-text")
        assertEquals("via relay", text.filterIsInstance<GatewayEvent.OutputDone>().single().text)

        val header = AudioChannel(requestId = "r-audio", session = "loopback-relay-audio", locale = "en-US").headerBytes()
        relay.handleAudioChannel("watch", ByteArrayInputStream(header + ByteArray(16000) { 1 })) // 500 ms
        val audio = drain(sent, "r-audio")
        assertEquals("fake transcript of 500 ms at 16000 Hz in en-US", audio.filterIsInstance<GatewayEvent.InputTranscript>().single().text)
        assertEquals("turn.completed", audio.last().typeName)

        // and an approval answered from the watch side
        relay.handleMessage("watch", WatchProtocol.PATH_TURN_TEXT, WatchProtocol.json.encodeToString(TextTurn.serializer(), TextTurn("r-apr", "approve send it", session = "loopback-relay-apr")).encodeToByteArray())
        var done = false
        val collected = mutableListOf<GatewayEvent>()
        while (!done) {
            val (path, payload) = sent.poll(10, TimeUnit.SECONDS) ?: error("relay went quiet")
            assertEquals(WatchProtocol.PATH_EVENT, path)
            val relayed = WatchProtocol.json.decodeFromString(RelayedEvent.serializer(), payload.decodeToString())
            if (relayed.requestId != "r-apr") continue
            val e = relayed.event()
            collected += e
            if (e is GatewayEvent.ApprovalRequired) {
                relay.handleMessage("watch", WatchProtocol.PATH_APPROVAL, WatchProtocol.json.encodeToString(ApprovalAnswer.serializer(), ApprovalAnswer("r-apr", e.turnId, e.approvalId, "allow")).encodeToByteArray())
            }
            if (e is GatewayEvent.TurnCompleted) done = true
        }
        assertEquals("Done.", collected.filterIsInstance<GatewayEvent.OutputDone>().single().text)
    }

    private fun drain(sent: LinkedBlockingQueue<Pair<String, ByteArray>>, requestId: String): List<GatewayEvent> {
        val out = mutableListOf<GatewayEvent>()
        while (true) {
            val (path, payload) = sent.poll(10, TimeUnit.SECONDS) ?: error("relay went quiet waiting for $requestId")
            assertEquals(WatchProtocol.PATH_EVENT, path)
            val relayed = WatchProtocol.json.decodeFromString(RelayedEvent.serializer(), payload.decodeToString())
            if (relayed.requestId != requestId) continue
            val e = relayed.event()
            out += e
            if (e is GatewayEvent.TurnCompleted || (e is GatewayEvent.Error && e.turnId.isEmpty())) return out
        }
    }
}
