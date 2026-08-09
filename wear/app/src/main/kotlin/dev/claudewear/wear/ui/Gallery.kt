package dev.claudewear.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.TimeSource
import androidx.wear.tooling.preview.devices.WearDevices
import dev.claudewear.protocol.PermissionMode
import dev.claudewear.protocol.SessionState
import dev.claudewear.protocol.SessionSummary

/**
 * Every screen state worth looking at, defined once.
 *
 * Three things read this file and they must not be allowed to disagree: Android Studio
 * renders the `@Preview`s in the pane, `ScreenshotTest` captures them as committed goldens
 * on a Robolectric Wear device, and `ScreenTourTest` captures them again on the emulator
 * `scripts/e2e.sh` already boots. [gallery] is what the two tests iterate, so adding a
 * state here is the whole of adding it everywhere — there is no second list to forget.
 *
 * The screens can be posed like this at all because they are pure functions of
 * [WatchState] plus [WatchActions]; nothing here fakes a transport or a service.
 */
internal object Gallery {

    /**
     * A screen under one named state. The name is the picture's filename.
     *
     * A round 227dp screen shows about four rows, and several of these states are only
     * themselves further down the list — the refusal on the pairing screen, the mode that is
     * currently selected. [reveal] names the text that has to be on screen for the picture to
     * be worth taking, and the list is scrolled until it is. Null means the top already says
     * it.
     */
    data class Pose(
        val name: String,
        val reveal: String? = null,
        val content: @Composable () -> Unit,
    )

    /**
     * Ordered as a walk through the app — pair, then the list, then a chat, then the two
     * screens you reach from it — because this doubles as the tour a reviewer scrolls through.
     */
    val gallery: List<Pose> = listOf(
        Pose("pair-empty") { PairEmptyPreview() },
        Pose("pair-busy", reveal = "Pairing…") { PairBusyPreview() },
        Pose("pair-refused", reveal = "that code has expired") { PairRefusedPreview() },
        Pose("sessions-connecting") { SessionsConnectingPreview() },
        Pose("sessions-one-waiting") { SessionsOneWaitingPreview() },
        Pose("sessions-empty") { SessionsEmptyPreview() },
        Pose("sessions-offline", reveal = "the bridge stopped answering") { SessionsOfflinePreview() },
        Pose("sessions-at-cap", reveal = "this bridge's limit") { SessionsAtCapPreview() },
        Pose("chat-waiting", reveal = "waiting on you") { ChatWaitingPreview() },
        Pose("chat-problem", reveal = "ANTHROPIC_API_KEY") { ChatProblemPreview() },
        Pose("chat-gone") { ChatGonePreview() },
        Pose("new-chat-roots") { NewChatRootsPreview() },
        Pose("new-chat-permissive") { NewChatPermissivePreview() },
        Pose("modes-default") { ModesDefaultPreview() },
        Pose("modes-bypass", reveal = "stops asking") { ModesBypassPreview() },
    )
}

// --- the states ---------------------------------------------------------------------------

private const val CWD = "/home/you/code/claude-wear"
private const val BRIDGE = "http://192.168.1.24:8787"
private const val LINTER = "sess_linter"
private const val DOCS = "sess_docs"

/**
 * Fixed timestamps, not `System.currentTimeMillis()`: `awaitingFirst` sorts on `createdAt`,
 * and a golden whose row order depends on the clock is a golden that fails on Tuesdays.
 */
private fun summary(
    sessionId: String,
    name: String,
    state: SessionState,
    pending: List<String> = emptyList(),
    mode: PermissionMode = PermissionMode.DEFAULT,
    createdAt: Long = 1_700_000_000_000,
) = SessionSummary(
    sessionId = sessionId,
    name = name,
    cwd = CWD,
    state = state,
    mode = mode,
    seq = 7,
    pendingRequestIds = pending,
    createdAt = createdAt,
    lastActivityAt = createdAt,
)

private val linter = SessionView(
    summary(LINTER, "linter", SessionState.AWAITING, pending = listOf("req_1"), createdAt = 1_700_000_000_000),
)
private val docs = SessionView(
    summary(DOCS, "docs", SessionState.WORKING, createdAt = 1_700_000_060_000),
)

private val connected = WatchState(
    connection = WatchState.Connection.CONNECTED,
    maxSessions = 5,
    projectRoots = listOf(CWD, "/home/you/code/bridge-notes"),
)

private val oneWaiting = connected.copy(sessions = listOf(linter, docs))

private val transcript = listOf(
    TranscriptLine(TranscriptLine.Kind.YOU, "run the linter and fix what it finds"),
    TranscriptLine(TranscriptLine.Kind.CLAUDE, "Ran ktlint. 3 findings, all in ChatScreen.kt."),
    TranscriptLine(TranscriptLine.Kind.CLAUDE, "Two are import order. The third is a long line."),
    TranscriptLine(TranscriptLine.Kind.WAITING, "may I run `./gradlew :app:lintDebug`?"),
)

// --- pairing ------------------------------------------------------------------------------

@Preview(name = "Pair — empty", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun PairEmptyPreview() = OnAWatch {
    PairScreen(defaultBridgeUrl = BRIDGE, pairing = PairingState(), onPair = { _, _ -> })
}

@Preview(name = "Pair — pairing", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun PairBusyPreview() = OnAWatch {
    PairScreen(defaultBridgeUrl = BRIDGE, pairing = PairingState(busy = true), onPair = { _, _ -> })
}

@Preview(name = "Pair — refused", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun PairRefusedPreview() = OnAWatch {
    PairScreen(
        defaultBridgeUrl = BRIDGE,
        pairing = PairingState(error = "that code has expired"),
        onPair = { _, _ -> },
    )
}

// --- the list -----------------------------------------------------------------------------

@Preview(name = "Sessions — connecting", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun SessionsConnectingPreview() = OnAWatch {
    Sessions(WatchState(connection = WatchState.Connection.CONNECTING))
}

/** The screen the product exists for: who needs you, first, in the accent colour. */
@Preview(name = "Sessions — one waiting", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun SessionsOneWaitingPreview() = OnAWatch { Sessions(oneWaiting) }

@Preview(name = "Sessions — no chats", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun SessionsEmptyPreview() = OnAWatch { Sessions(connected) }

@Preview(name = "Sessions — offline", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun SessionsOfflinePreview() = OnAWatch {
    Sessions(
        oneWaiting.copy(
            connection = WatchState.Connection.OFFLINE,
            error = "the bridge stopped answering",
        ),
    )
}

/** `New chat` has to be visibly unavailable, and the cap has to say whose it is. */
@Preview(name = "Sessions — at the cap", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun SessionsAtCapPreview() = OnAWatch {
    Sessions(oneWaiting.copy(maxSessions = 2))
}

@Composable
private fun Sessions(state: WatchState) =
    SessionsScreen(state = state, onOpen = {}, onNewChat = {}, onUnpair = {})

// --- a chat -------------------------------------------------------------------------------

@Preview(name = "Chat — waiting on you", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun ChatWaitingPreview() = OnAWatch {
    Chat(oneWaiting.copy(transcripts = mapOf(LINTER to transcript)), LINTER)
}

@Preview(name = "Chat — session error", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun ChatProblemPreview() = OnAWatch {
    val failed = SessionView(summary(DOCS, "docs", SessionState.ERROR))
    Chat(
        connected.copy(
            sessions = listOf(failed),
            transcripts = mapOf(
                DOCS to listOf(
                    TranscriptLine(TranscriptLine.Kind.YOU, "summarise the README"),
                    TranscriptLine(TranscriptLine.Kind.PROBLEM, "the agent exited: ANTHROPIC_API_KEY is not set"),
                ),
            ),
        ),
        DOCS,
    )
}

/** A chat the bridge no longer has. Reachable by swiping back into a stale nav entry. */
@Preview(name = "Chat — gone", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun ChatGonePreview() = OnAWatch { Chat(connected, "sess_vanished") }

@Composable
private fun Chat(state: WatchState, sessionId: String) =
    ChatScreen(state = state, sessionId = sessionId, onPrompt = {}, onInterrupt = {}, onModes = {})

// --- starting one -------------------------------------------------------------------------

@Preview(name = "New chat — roots", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun NewChatRootsPreview() = OnAWatch { NewChatScreen(state = connected, onStart = {}) }

/** No roots configured, which is the shipped default and the case the copy has to warn about. */
@Preview(name = "New chat — permissive bridge", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun NewChatPermissivePreview() = OnAWatch {
    NewChatScreen(state = connected.copy(projectRoots = emptyList()), onStart = {})
}

// --- permissions --------------------------------------------------------------------------

@Preview(name = "Permissions — default", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun ModesDefaultPreview() = OnAWatch { ModeScreen(current = PermissionMode.DEFAULT, onPick = {}) }

@Preview(name = "Permissions — bypass", device = WearDevices.SMALL_ROUND, showBackground = true)
@Composable
internal fun ModesBypassPreview() = OnAWatch {
    ModeScreen(current = PermissionMode.BYPASS_PERMISSIONS, onPick = {})
}

/**
 * The theme `MainActivity` puts the app in — without it a preview and a golden would both be
 * rendering colours the real app never shows — and a clock that does not move.
 */
@Composable
private fun OnAWatch(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalTimeSource provides StoppedClock) {
        MaterialTheme(content = content)
    }

/** Ten past ten, forever. The reading does not matter; that it never changes does. */
private object StoppedClock : TimeSource {
    override val currentTime: String
        @Composable get() = "10:09"
}
