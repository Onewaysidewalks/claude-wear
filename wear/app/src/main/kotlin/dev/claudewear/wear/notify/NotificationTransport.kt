package dev.claudewear.wear.notify

import dev.claudewear.protocol.TurnEvent

/**
 * How the wearer finds out it is their turn.
 *
 * [SocketNotifications] is today's implementation and the one CI exercises. An FCM one
 * would let the watch sleep instead of holding a socket, but it needs a Firebase project
 * and cannot be tested hermetically — so it ships behind this interface if it ships at all.
 * The protocol already carries everything a push payload would need.
 */
fun interface NotificationTransport {
    fun onTurn(event: TurnEvent)
}
