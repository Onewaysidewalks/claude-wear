package dev.claudewear.phone.data

import dev.claudewear.shared.ApprovalDecision
import dev.claudewear.shared.AudioSpec
import dev.claudewear.shared.GatewayEvent
import dev.claudewear.shared.GatewayEvents
import dev.claudewear.shared.GatewayJson
import dev.claudewear.shared.SessionRef
import dev.claudewear.shared.TextInput
import dev.claudewear.shared.TextTurnRequest
import dev.claudewear.shared.TurnOptions
import dev.claudewear.shared.WsSimple
import dev.claudewear.shared.WsStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The phone's view of gateway/API.md. One class, no Android in it, so it is tested against
 * MockWebServer. Every method throws [GatewayException] for an HTTP-level refusal and
 * [IOException] for the network.
 */
/** The subset of the gateway a relay needs; [GatewayClient] is the real one, tests use a fake. */
interface TurnGateway {
    fun textTurn(text: String, session: String = "default", locale: String = "en-US", reset: Boolean = false): Flow<GatewayEvent>
    fun audioTurn(pcm: Flow<ByteArray>, spec: AudioSpec, session: String = "watch", locale: String = "en-US"): Flow<GatewayEvent>
    fun approve(turnId: String, approvalId: String, decision: String)
    fun cancel(turnId: String)
}

class GatewayClient(
    private val config: () -> GatewayConfig,
    client: OkHttpClient? = null,
) : TurnGateway {
    class GatewayException(val status: Int, val code: String, message: String) : IOException("$status $code: $message")

    data class Health(val ok: Boolean, val version: String, val brainKind: String, val brainReachable: Boolean, val stt: String)

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE and WebSocket streams stay open while the brain thinks
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = config().baseUrl.trimEnd('/') + path

    private fun authed(builder: Request.Builder) = builder.header("Authorization", "Bearer ${config().token}")

    private fun Response.failIfError() {
        if (isSuccessful) return
        val body = body?.string().orEmpty()
        val err = runCatching { GatewayJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        throw GatewayException(
            code,
            err?.get("code")?.jsonPrimitive?.content ?: "http_$code",
            err?.get("message")?.jsonPrimitive?.content ?: body.take(200),
        )
    }

    fun health(): Health {
        http.newCall(Request.Builder().url(url("/v1/health")).build()).execute().use { r ->
            r.failIfError()
            val o = GatewayJson.parseToJsonElement(r.body!!.string()).jsonObject
            val brain = o["brain"]?.jsonObject
            return Health(
                ok = o["ok"]?.jsonPrimitive?.content == "true",
                version = o["version"]?.jsonPrimitive?.content ?: "",
                brainKind = brain?.get("kind")?.jsonPrimitive?.content ?: "",
                brainReachable = brain?.get("reachable")?.jsonPrimitive?.content == "true",
                stt = o["stt"]?.jsonPrimitive?.content ?: "",
            )
        }
    }

    /** The device name the token maps to, which is the pairing check. */
    fun whoami(): String {
        http.newCall(authed(Request.Builder().url(url("/v1/whoami"))).build()).execute().use { r ->
            r.failIfError()
            val o = GatewayJson.parseToJsonElement(r.body!!.string()).jsonObject
            return o["device"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "?"
        }
    }

    /** POST /v1/turns as a stream of events. Completes after turn.completed. */
    override fun textTurn(text: String, session: String, locale: String, reset: Boolean): Flow<GatewayEvent> = flow {
        val body = GatewayJson.encodeToString(
            TextTurnRequest.serializer(),
            TextTurnRequest(session = SessionRef(session, reset), input = TextInput(text = text), options = TurnOptions(locale = locale)),
        )
        val request = authed(Request.Builder().url(url("/v1/turns")))
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { r ->
            r.failIfError()
            val source = r.body!!.source()
            val parser = SseParser()
            while (true) {
                val line = source.readUtf8Line() ?: break
                val data = parser.feed(line) ?: continue
                val event = GatewayEvents.decode(data)
                emit(event)
                if (event is GatewayEvent.TurnCompleted) break
            }
        }
    }

    override fun approve(turnId: String, approvalId: String, decision: String) {
        val body = GatewayJson.encodeToString(ApprovalDecision.serializer(), ApprovalDecision(decision)).toRequestBody(JSON)
        http.newCall(authed(Request.Builder().url(url("/v1/turns/$turnId/approvals/$approvalId"))).post(body).build())
            .execute().use { it.failIfError() }
    }

    override fun cancel(turnId: String) {
        http.newCall(authed(Request.Builder().url(url("/v1/turns/$turnId/cancel"))).post(ByteArray(0).toRequestBody(JSON)).build())
            .execute().use { it.failIfError() }
    }

    /**
     * One utterance over WS /v1/stream. [pcm] yields raw PCM chunks; when it completes, `end` is
     * sent and the events of the resulting turn follow. The flow completes after turn.completed.
     */
    override fun audioTurn(pcm: Flow<ByteArray>, spec: AudioSpec, session: String, locale: String): Flow<GatewayEvent> = callbackFlow {
        val wsUrl = url("/v1/stream").replaceFirst("http", "ws")
        val request = authed(Request.Builder().url(wsUrl)).build()
        var socket: WebSocket? = null
        var finished = false
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(GatewayJson.encodeToString(WsStart.serializer(), WsStart(session = SessionRef(session), audio = spec, options = TurnOptions(locale = locale))))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj: JsonObject = runCatching { GatewayJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (obj["type"]?.jsonPrimitive?.content) {
                    "hello", "pong" -> Unit
                    "listening" -> launch {
                        try {
                            pcm.collect { chunk -> webSocket.send(chunk.toByteString()) }
                            webSocket.send(GatewayJson.encodeToString(WsSimple.serializer(), WsSimple("end")))
                        } catch (t: Throwable) {
                            close(t)
                        }
                    }
                    else -> {
                        val event = GatewayEvents.decode(text)
                        trySend(event)
                        if (event is GatewayEvent.TurnCompleted || (event is GatewayEvent.Error && event.turnId.isEmpty())) {
                            finished = true
                            webSocket.close(1000, "done")
                            close()
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(if (response != null && response.code >= 400) GatewayException(response.code, "ws_${response.code}", response.message) else t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }
        socket = http.newWebSocket(request, listener)
        // A finished turn closes with a handshake; anything else (collector gone) tears down hard.
        awaitClose { if (!finished) socket?.cancel() }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

/** A minimal text/event-stream line parser: returns the `data` of an event when its blank line arrives. */
class SseParser {
    private val data = StringBuilder()
    var lastEventName: String? = null
        private set

    fun feed(line: String): String? {
        when {
            line.isEmpty() -> {
                if (data.isEmpty()) return null
                val out = data.toString()
                data.setLength(0)
                return out
            }
            line.startsWith(":") -> return null
            line.startsWith("event:") -> lastEventName = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart())
            }
        }
        return null
    }
}
