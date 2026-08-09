package dev.claudewear.wear.ui

import dev.claudewear.protocol.PermissionMode
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.SessionSummary
import dev.claudewear.protocol.TurnEvent

/**
 * Everything the screens render. In memory only: no transcript content is persisted, which
 * is both a privacy position and the reason this can stay a plain data class.
 */
data class WatchState(
    val connection: Connection = Connection.CONNECTING,
    /** Awaiting first, then idle, then everything else — "who needs me" is the whole point. */
    val sessions: List<SessionView> = emptyList(),
    val maxSessions: Int = 0,
    /** Directories the bridge will open. Empty means it is configured permissively. */
    val projectRoots: List<String> = emptyList(),
    val lastTurn: TurnEvent? = null,
    /**
     * Kept out of the per-session transcripts on purpose: this is the connection-scoped
     * problem, the one the Sessions screen has to show even with no chats open.
     */
    val error: String? = null,
    /**
     * Keyed by sessionId rather than held on [SessionView], because a chat's first `text`
     * can arrive before the snapshot that names it and dropping that line would be a lie.
     */
    val transcripts: Map<String, List<TranscriptLine>> = emptyMap(),
) {
    enum class Connection { CONNECTING, CONNECTED, OFFLINE }

    val connected: Boolean get() = connection == Connection.CONNECTED

    val awaiting: List<SessionView> get() = sessions.filter { it.awaiting }

    fun session(sessionId: String): SessionView? = sessions.find { it.sessionId == sessionId }

    fun transcript(sessionId: String): List<TranscriptLine> = transcripts[sessionId].orEmpty()

    /** `maxSessions == 0` means no snapshot has landed yet, not a bridge that allows none. */
    val canOpenAnother: Boolean get() = maxSessions == 0 || sessions.size < maxSessions
}

/**
 * One chat, as the watch currently understands it.
 *
 * [state] and [pendingRequestIds] shadow the ones on [summary] because snapshots only go
 * out when the *set* of chats changes: `turn`, `ask`, `permission` and `resolved` are what
 * keep a list of five chats from claiming that all of them are still working.
 */
data class SessionView(
    val summary: SessionSummary,
    val state: SessionState,
    val pendingRequestIds: List<String>,
) {
    constructor(summary: SessionSummary) : this(summary, summary.state, summary.pendingRequestIds)

    val sessionId: String get() = summary.sessionId
    val name: String get() = summary.name
    val cwd: String get() = summary.cwd
    val mode: PermissionMode get() = summary.mode
    val awaiting: Boolean get() = pendingRequestIds.isNotEmpty()
}

/**
 * A condensed transcript line. The watch never gets token-by-token deltas — a wrist does
 * not need them and not sending them is a real battery saving — so a line is the unit.
 */
data class TranscriptLine(val kind: Kind, val text: String) {
    enum class Kind {
        /** Assistant text. */
        CLAUDE,

        /** Something you sent, echoed locally: the bridge does not reflect prompts back. */
        YOU,

        /** A question or permission the agent is blocked on. The real cards are M3. */
        WAITING,

        /** The result of a turn. */
        RESULT,

        /** A session-scoped error. */
        PROBLEM,
    }
}

/** Awaiting first, then idle, then everything else; oldest first within a group. */
internal fun List<SessionView>.awaitingFirst(): List<SessionView> = sortedWith(
    compareBy<SessionView> {
        when {
            it.awaiting -> 0
            it.state == SessionState.IDLE -> 1
            else -> 2
        }
    }.thenBy { it.summary.createdAt }.thenBy { it.sessionId },
)
