package dev.claudewear.wear.transport

import dev.claudewear.protocol.ClientMessage
import dev.claudewear.protocol.ServerEvent
import kotlinx.coroutines.flow.Flow

/**
 * How the watch reaches the bridge.
 *
 * [WebSocketTransport] is the only implementation today. A phone relay over the Wear Data
 * Layer API is the deferred second one, and it exists as an interface from day one so that
 * work is a new class and a binding rather than a change to any ViewModel or screen.
 */
interface ClientTransport {
    /** Connects, and emits every event the bridge sends until the flow is cancelled. */
    fun connect(): Flow<ServerEvent>

    /** Suspends until there is a live connection, then sends. */
    suspend fun send(msg: ClientMessage)
}
