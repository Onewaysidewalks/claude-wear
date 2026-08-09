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
)

private object Route {
    const val PAIR = "pair"
    const val SESSIONS = "sessions"
    const val NEW_CHAT = "new"
    const val CHAT = "chat/{sessionId}"
    const val MODE = "mode/{sessionId}"

    fun chat(sessionId: String) = "chat/$sessionId"
    fun mode(sessionId: String) = "mode/$sessionId"
}

/**
 * Pair → Sessions → Chat, with swipe-to-dismiss as the back gesture because that is what
 * a round screen with no back button has.
 */
@Composable
fun ClaudeWearApp(
    paired: Boolean,
    state: WatchState,
    defaultBridgeUrl: String,
    pairing: PairingState,
    actions: WatchActions,
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
        }
    }
}
