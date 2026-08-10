package dev.claudewear.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.claudewear.protocol.PermissionMode

/** How a pairing attempt is going, which is the one thing the Pair screen cannot derive. */
data class PairingState(val busy: Boolean = false, val error: String? = null)

/**
 * Everything a screen can ask for, bound once in `MainActivity`.
 *
 * A single bag rather than a dozen lambdas threaded through the nav graph: the screens are
 * pure functions of [WatchState] plus this, which is what keeps them previewable and keeps
 * the connection in the service where it belongs.
 */
data class WatchActions(
    val pair: (baseUrl: String, code: String) -> Unit = { _, _ -> },
    val unpair: () -> Unit = {},
    val newSession: (cwd: String) -> Unit = {},
    val prompt: (sessionId: String, text: String) -> Unit = { _, _ -> },
    val interrupt: (sessionId: String) -> Unit = {},
    val setMode: (sessionId: String, mode: PermissionMode) -> Unit = { _, _ -> },
    /** question text -> the chosen label, or dictated free text. */
    val answer: (sessionId: String, requestId: String, answers: Map<String, String>) -> Unit = { _, _, _ -> },
    /** Dismiss an AskUserQuestion and just talk to Claude instead. */
    val respond: (sessionId: String, requestId: String, response: String) -> Unit = { _, _, _ -> },
    val allow: (sessionId: String, requestId: String, always: Boolean) -> Unit = { _, _, _ -> },
    val deny: (sessionId: String, requestId: String, reason: String?) -> Unit = { _, _, _ -> },
    /**
     * The platform speech recognizer. The result callback only fires with something worth
     * sending — a cancelled or empty dictation is not an answer — so no screen has to guard
     * against a blank.
     */
    val dictate: (prompt: String, onResult: (String) -> Unit) -> Unit = { _, _ -> },
)

private object Route {
    const val PAIR = "pair"
    const val SESSIONS = "sessions"
    const val NEW_CHAT = "new"
    const val CHAT = "chat/{sessionId}"
    const val MODE = "mode/{sessionId}"

    /**
     * Keyed by requestId alone, not by session: the id is unique across chats, and it is
     * what a notification's reply action carries. A card that outlived its request can then
     * say so instead of rendering somebody else's question.
     */
    const val CARD = "card/{requestId}"

    fun chat(sessionId: String) = "chat/$sessionId"
    fun mode(sessionId: String) = "mode/$sessionId"
    fun card(requestId: String) = "card/$requestId"
}

/**
 * Pair → Sessions → Chat → the card you were buzzed about, with swipe-to-dismiss as the back
 * gesture because that is what a round screen with no back button has.
 */
@Composable
fun ClaudeWearApp(
    paired: Boolean,
    state: WatchState,
    defaultBridgeUrl: String,
    pairing: PairingState,
    actions: WatchActions,
    /** A notification you tapped: the chat to open, and the card on it if there is one. */
    opening: Opening? = null,
    onOpened: () -> Unit = {},
) {
    MaterialTheme {
        val nav = rememberSwipeDismissableNavController()

        // Pairing and unpairing both happen underneath the nav graph, so the graph follows
        // rather than leads; popping to the root stops a swipe going back to a screen that
        // has no connection behind it any more.
        LaunchedEffect(paired) {
            val destination = if (paired) Route.SESSIONS else Route.PAIR
            if (nav.currentDestination?.route != destination) {
                nav.navigate(destination) { popUpTo(nav.graph.id) { inclusive = true } }
            }
        }

        // Tapping a card in the shade should land on the thing that buzzed you, not on the
        // list. The chat goes on the back stack under it, so a swipe leaves you somewhere
        // that makes sense rather than dumping you out of the app.
        LaunchedEffect(opening) {
            val target = opening ?: return@LaunchedEffect
            if (paired) {
                nav.navigate(Route.chat(target.sessionId))
                if (target.requestId != null) nav.navigate(Route.card(target.requestId))
            }
            onOpened()
        }

        SwipeDismissableNavHost(
            navController = nav,
            startDestination = if (paired) Route.SESSIONS else Route.PAIR,
        ) {
            composable(Route.PAIR) {
                PairScreen(defaultBridgeUrl = defaultBridgeUrl, pairing = pairing, onPair = actions.pair)
            }

            composable(Route.SESSIONS) {
                SessionsScreen(
                    state = state,
                    onOpen = { nav.navigate(Route.chat(it)) },
                    onNewChat = { nav.navigate(Route.NEW_CHAT) },
                    onUnpair = actions.unpair,
                )
            }

            composable(Route.NEW_CHAT) {
                NewChatScreen(
                    state = state,
                    onStart = { cwd ->
                        actions.newSession(cwd)
                        nav.popBackStack()
                    },
                )
            }

            composable(Route.CHAT) { entry ->
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                ChatScreen(
                    state = state,
                    sessionId = sessionId,
                    onPrompt = { text -> actions.prompt(sessionId, text) },
                    onInterrupt = { actions.interrupt(sessionId) },
                    onModes = { nav.navigate(Route.mode(sessionId)) },
                    onOpenRequest = { nav.navigate(Route.card(it)) },
                    onDictate = actions.dictate,
                )
            }

            composable(Route.MODE) { entry ->
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                ModeScreen(
                    current = state.session(sessionId)?.mode,
                    onPick = { mode ->
                        actions.setMode(sessionId, mode)
                        nav.popBackStack()
                    },
                )
            }

            composable(Route.CARD) { entry ->
                val requestId = entry.arguments?.getString("requestId").orEmpty()
                // Answering pops the card. The `resolved` that follows drops the request from
                // the state, and a screen still sitting on it would flip to "answered" under
                // your thumb.
                val answered = { nav.popBackStack() }
                when (val request = state.request(requestId)) {
                    is PendingRequest.Ask -> QuestionScreen(
                        request = request,
                        sessionName = state.session(request.sessionId)?.name.orEmpty(),
                        onAnswer = {
                            actions.answer(request.sessionId, requestId, it)
                            answered()
                        },
                        onRespond = {
                            actions.respond(request.sessionId, requestId, it)
                            answered()
                        },
                        onDictate = actions.dictate,
                    )

                    is PendingRequest.Permission -> PermissionScreen(
                        request = request,
                        sessionName = state.session(request.sessionId)?.name.orEmpty(),
                        onAllow = {
                            actions.allow(request.sessionId, requestId, false)
                            answered()
                        },
                        onAlways = {
                            actions.allow(request.sessionId, requestId, true)
                            answered()
                        },
                        onDeny = {
                            actions.deny(request.sessionId, requestId, it)
                            answered()
                        },
                        onDictate = actions.dictate,
                    )

                    // Dealt with somewhere else, or the agent gave up waiting.
                    null -> QuestionScreen(
                        request = null,
                        sessionName = "",
                        onAnswer = {},
                        onRespond = {},
                        onDictate = actions.dictate,
                    )
                }
            }
        }
    }
}

/** A tapped notification, as the Activity hands it to the nav graph. */
data class Opening(val sessionId: String, val requestId: String?)
