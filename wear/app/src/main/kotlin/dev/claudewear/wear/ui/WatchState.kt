package dev.claudewear.wear.ui

import dev.claudewear.protocol.SessionSummary
import dev.claudewear.protocol.TurnEvent

/** Everything the stub UI renders. No transcript content is persisted beyond this. */
data class WatchState(
    val connected: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val maxSessions: Int = 0,
    val lastTurn: TurnEvent? = null,
    /** Condensed, in-memory, newest last. M3 turns the waiting entries into real cards. */
    val transcript: List<String> = emptyList(),
    val error: String? = null,
) {
    val awaiting: List<SessionSummary>
        get() = sessions.filter { it.pendingRequestIds.isNotEmpty() }
}
