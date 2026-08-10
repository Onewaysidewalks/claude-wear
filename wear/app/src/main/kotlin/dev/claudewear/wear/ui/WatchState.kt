package dev.claudewear.wear.ui

import dev.claudewear.protocol.AskQuestion
import dev.claudewear.protocol.PermissionMode
import dev.claudewear.protocol.PermissionSuggestion
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
    /**
     * Every block the agent is parked on, keyed by requestId — the id the bridge mints, so
     * it is unique across chats and a dictated reply from the shade can name exactly the
     * request it was attached to. Insertion-ordered, which is oldest-first.
     */
    val pending: Map<String, PendingRequest> = emptyMap(),
) {
    enum class Connection { CONNECTING, CONNECTED, OFFLINE }

    val connected: Boolean get() = connection == Connection.CONNECTED

    val awaiting: List<SessionView> get() = sessions.filter { it.awaiting }

    fun session(sessionId: String): SessionView? = sessions.find { it.sessionId == sessionId }

    fun transcript(sessionId: String): List<TranscriptLine> = transcripts[sessionId].orEmpty()

    fun request(requestId: String): PendingRequest? = pending[requestId]

    /** What this chat is blocked on, oldest first. */
    fun pending(sessionId: String): List<PendingRequest> = pending.values.filter { it.sessionId == sessionId }

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
 * One block the agent is parked on, with everything its card needs to draw itself.
 *
 * Held rather than summarised, because the card's whole job is to show you the real thing:
 * the questions as Claude asked them, and the actual command rather than a description of
 * one. Approving what you cannot see is the failure mode this product designs against, and
 * it gets worse the smaller the screen.
 */
sealed interface PendingRequest {
    val sessionId: String
    val requestId: String

    /** One line, for the chat transcript and the notification. */
    val summary: String

    data class Ask(
        override val sessionId: String,
        override val requestId: String,
        val questions: List<AskQuestion>,
    ) : PendingRequest {
        override val summary: String get() = questions.firstOrNull()?.question ?: "a question"
    }

    data class Permission(
        override val sessionId: String,
        override val requestId: String,
        val tool: String,
        /** The actual command or path, verbatim from the bridge. Never shortened here. */
        val display: String,
        val suggestions: List<PermissionSuggestion>,
    ) : PendingRequest {
        override val summary: String get() = "$tool — $display"

        /**
         * The rules an "always allow" would write, which is the only honest label for that
         * button. The bridge only persists `localSettings` suggestions — a wrist tap should
         * not edit your user-level config — so an offer with none of those is not an offer.
         */
        val alwaysRules: List<String>
            get() = suggestions
                .filter { it.destination == "localSettings" }
                .flatMap { it.rules }
                .mapNotNull { it.ruleContent ?: it.toolName }
    }
}

/**
 * A condensed transcript line. The watch never gets token-by-token deltas — a wrist does
 * not need them and not sending them is a real battery saving — so a line is the unit.
 */
data class TranscriptLine(val kind: Kind, val text: String, val requestId: String? = null) {
    enum class Kind {
        /** Assistant text. */
        CLAUDE,

        /** Something you sent, echoed locally: the bridge does not reflect prompts back. */
        YOU,

        /** A question or permission the agent is blocked on; [requestId] opens its card. */
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
