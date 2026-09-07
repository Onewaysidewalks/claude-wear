package dev.claudewear.phone.data

import dev.claudewear.shared.AudioSpec
import dev.claudewear.shared.GatewayEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class GatewayClientTest {
    private val server = MockWebServer()
    private lateinit var client: GatewayClient

    private val http = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()

    @Before
    fun up() {
        server.start()
        client = GatewayClient({ GatewayConfig(server.url("/").toString(), "hg1_test") }, http)
    }

    @After
    fun down() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
    }

    private fun sse(vararg events: Pair<String, String>) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })

    @Test
    fun `health and whoami`() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"api":"v1","version":"1.0.0","brain":{"kind":"fake","reachable":true},"stt":"none","tts":"none"}"""))
        server.enqueue(MockResponse().setBody("""{"device":{"id":"dev_1","name":"pixel"}}"""))
        val h = client.health()
        assertEquals("1.0.0", h.version)
        assertTrue(h.brainReachable)
        assertEquals("pixel", client.whoami())
        server.takeRequest()
        val who: RecordedRequest = server.takeRequest()
        assertEquals("Bearer hg1_test", who.getHeader("Authorization"))
        assertEquals("/v1/whoami", who.path)
    }

    @Test
    fun `text turn parses the stream and stops after completion`() = runBlocking {
        server.enqueue(
            sse(
                "turn.started" to """{"type":"turn.started","turn_id":"t1","seq":1,"at":"x","session_key":"phone","input_type":"text"}""",
                "output.delta" to """{"type":"output.delta","turn_id":"t1","seq":2,"at":"x","text":"hi"}""",
                "output.done" to """{"type":"output.done","turn_id":"t1","seq":3,"at":"x","text":"hi"}""",
                "turn.completed" to """{"type":"turn.completed","turn_id":"t1","seq":4,"at":"x","status":"ok"}""",
            ),
        )
        val events = client.textTurn("hello", session = "phone").toList()
        assertEquals(listOf("turn.started", "output.delta", "output.done", "turn.completed").size, events.size)
        assertEquals("hi", (events[2] as GatewayEvent.OutputDone).text)
        val req = server.takeRequest()
        assertEquals("/v1/turns", req.path)
        assertEquals("text/event-stream", req.getHeader("Accept"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"text\":\"hello\"") && body.contains("\"key\":\"phone\""))
    }

    @Test
    fun `gateway refusals become GatewayException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"session_busy","message":"a turn is already running"}}"""))
        val err = runCatching { client.textTurn("x").toList() }.exceptionOrNull() as GatewayClient.GatewayException
        assertEquals(409, err.status)
        assertEquals("session_busy", err.code)
    }

    @Test
    fun `approve and cancel hit the right paths`() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        client.approve("t1", "a1", "deny")
        client.cancel("t1")
        assertEquals("/v1/turns/t1/approvals/a1", server.takeRequest().also { assertEquals("""{"decision":"deny"}""", it.body.readUtf8()) }.path)
        assertEquals("/v1/turns/t1/cancel", server.takeRequest().path)
    }

    @Test
    fun `audio turn streams pcm over the websocket then reads events`() = runBlocking {
        val received = mutableListOf<Any>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("""{"type":"hello","api":"v1","version":"1.0.0","device":"pixel"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    when {
                        text.contains("\"start\"") -> webSocket.send("""{"type":"listening"}""")
                        text.contains("\"end\"") -> {
                            webSocket.send("""{"type":"turn.started","turn_id":"t2","seq":1,"at":"x","session_key":"watch","input_type":"audio"}""")
                            webSocket.send("""{"type":"input.transcript","turn_id":"t2","seq":2,"at":"x","text":"lights off","final":true}""")
                            webSocket.send("""{"type":"output.done","turn_id":"t2","seq":3,"at":"x","text":"Done."}""")
                            webSocket.send("""{"type":"turn.completed","turn_id":"t2","seq":4,"at":"x","status":"ok"}""")
                            webSocket.close(1000, "done") // the gateway closes after the last event
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    received += bytes.size
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }),
        )
        val pcm = flowOf(ByteArray(3200) { 1 }, ByteArray(1600) { 2 })
        val events = client.audioTurn(pcm, AudioSpec(sampleRate = 16000), session = "watch", locale = "en-GB").toList()
        assertEquals(listOf("turn.started", "input.transcript", "output.done", "turn.completed"), events.map { it.javaClass.simpleName.replace("TurnStarted", "turn.started").replace("InputTranscript", "input.transcript").replace("OutputDone", "output.done").replace("TurnCompleted", "turn.completed") })
        assertEquals("lights off", (events[1] as GatewayEvent.InputTranscript).text)
        val start = received[0] as String
        assertTrue(start, start.contains("\"type\":\"start\"") && start.contains("\"sample_rate\":16000") && start.contains("\"locale\":\"en-GB\""))
        assertEquals(listOf<Any>(3200, 1600), received.subList(1, 3))
        assertTrue((received[3] as String).contains("\"end\""))
        assertEquals("Bearer hg1_test", server.takeRequest().getHeader("Authorization"))
    }
}
