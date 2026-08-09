package dev.claudewear.wear.transport

import android.util.Log
import dev.claudewear.protocol.ClientMessage
import dev.claudewear.protocol.ProtocolJson
import dev.claudewear.protocol.ServerEvent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val TAG = "WebSocketTransport"

/**
 * Direct WSS-or-WS to the bridge, with the device token on the upgrade request.
 *
 * Reconnection is the caller's business: the flow completes (or fails) and whoever
 * collected it decides whether to back off and try again. That keeps this class small
 * enough to be obviously correct and leaves the policy where it can be tested.
 */
class WebSocketTransport(
    baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = defaultClient(),
) : ClientTransport {

    private val url = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws"
    private val live = MutableStateFlow<WebSocket?>(null)

    override fun connect(): Flow<ServerEvent> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "connected to $url")
                live.value = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = try {
                    ProtocolJson.decodeFromString<ServerEvent>(text)
                } catch (e: Exception) {
                    // Loud, not silent: an event this build cannot parse means the bridge
                    // is speaking a protocol the watch does not know.
                    close(e)
                    return
                }
                trySend(event)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failed (${response?.code})", t)
                live.value = null
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "socket closed: $code $reason")
                live.value = null
                close()
            }
        }

        val socket = client.newWebSocket(request, listener)
        awaitClose {
            live.value = null
            socket.cancel()
        }
    }

    override suspend fun send(msg: ClientMessage) {
        live.filterNotNull().first().send(ProtocolJson.encodeToString(msg))
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // A canUseTool request may sit unanswered for hours; the ping is what keeps the
            // socket alive across that without anything being sent on it.
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
