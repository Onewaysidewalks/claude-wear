package dev.claudewear.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.claudewear.protocol.PermissionDecision
import dev.claudewear.wear.data.DEFAULT_BRIDGE_URL
import dev.claudewear.wear.data.Pairing
import dev.claudewear.wear.data.TokenStore
import dev.claudewear.wear.service.ActiveConnection
import dev.claudewear.wear.service.ConnectionService
import dev.claudewear.wear.ui.ClaudeWearApp
import dev.claudewear.wear.ui.Opening
import dev.claudewear.wear.ui.PairingState
import dev.claudewear.wear.ui.WatchActions
import dev.claudewear.wear.ui.WatchState
import kotlinx.coroutines.launch

/**
 * The screens, and nothing else.
 *
 * The connection lives in [ConnectionService] — an Activity that owned the socket would
 * drop it the moment you lowered your wrist, which is exactly when a blocked agent needs
 * to reach you. Here that leaves pairing, the tapped notification that brought you in, and
 * the speech recognizer, all three of which need someone looking at the screen.
 */
class MainActivity : ComponentActivity() {

    private lateinit var tokens: TokenStore

    private var paired by mutableStateOf(false)
    private var pairing by mutableStateOf(PairingState())
    private var opening by mutableStateOf<Opening?>(null)

    /** Declined is survivable: the socket still runs, you just stop seeing the chip. */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Where the next recognized sentence goes. One dictation is in flight at a time. */
    private var onSpoken: ((String) -> Unit)? = null

    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        val waiting = onSpoken
        onSpoken = null
        // A cancelled or empty dictation is not an answer, and sending it as one would put
        // an empty string in front of Claude as though you had meant it.
        if (!spoken.isNullOrEmpty()) waiting?.invoke(spoken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = TokenStore(this)
        paired = tokens.isPaired
        opening = openingFrom(intent)
        requestNotificationPermission()
        if (paired) ConnectionService.start(this)

        setContent {
            val client by ActiveConnection.client.collectAsStateWithLifecycle()
            val state by ActiveConnection.state.collectAsStateWithLifecycle(WatchState())

            ClaudeWearApp(
                paired = paired,
                state = state,
                defaultBridgeUrl = tokens.baseUrl ?: DEFAULT_BRIDGE_URL,
                pairing = pairing,
                opening = opening,
                onOpened = { opening = null },
                actions = WatchActions(
                    pair = ::pair,
                    unpair = ::unpair,
                    newSession = { cwd -> client?.newSession(cwd) },
                    prompt = { sessionId, text -> client?.prompt(sessionId, text) },
                    interrupt = { sessionId -> client?.interrupt(sessionId) },
                    setMode = { sessionId, mode -> client?.setMode(sessionId, mode) },
                    answer = { sessionId, requestId, answers -> client?.answer(sessionId, requestId, answers) },
                    respond = { sessionId, requestId, response -> client?.respond(sessionId, requestId, response) },
                    allow = { sessionId, requestId, always ->
                        client?.decide(
                            sessionId,
                            requestId,
                            if (always) PermissionDecision.ALLOW_ALWAYS else PermissionDecision.ALLOW,
                        )
                    },
                    deny = { sessionId, requestId, reason ->
                        client?.decide(sessionId, requestId, PermissionDecision.DENY, reason)
                    },
                    dictate = ::dictate,
                ),
            )
        }
    }

    /** Tapped a second card while the app was already up. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opening = openingFrom(intent)
    }

    private fun openingFrom(intent: Intent?): Opening? {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return null
        return Opening(sessionId, intent.getStringExtra(EXTRA_REQUEST_ID))
    }

    /**
     * The platform recognizer: on-device, free, no extra API key, and it comes with the
     * keyboard and handwriting fallbacks the wearer already knows.
     */
    private fun dictate(prompt: String, onResult: (String) -> Unit) {
        onSpoken = onResult
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        runCatching { listen.launch(intent) }.onFailure {
            onSpoken = null
            // Every Wear device ships a recognizer, but a stripped emulator image does not,
            // and silently doing nothing would read as a broken button.
            Toast.makeText(this, "no speech input here — type instead", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pair(baseUrl: String, code: String) {
        pairing = PairingState(busy = true)
        lifecycleScope.launch {
            runCatching { Pairing.pair(baseUrl, code, deviceName()) }
                .onSuccess { result ->
                    tokens.save(baseUrl, result)
                    pairing = PairingState()
                    paired = true
                    ConnectionService.start(this@MainActivity)
                }
                .onFailure { pairing = PairingState(error = it.message) }
        }
    }

    /**
     * Forgets the token on this watch. The bridge keeps its own row for the device until
     * you revoke it there — losing a watch is a bridge-side revocation, and it is one
     * token, which is the whole reason tokens are per-device.
     */
    private fun unpair() {
        ConnectionService.stop(this)
        tokens.clear()
        pairing = PairingState()
        paired = false
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun deviceName(): String = Build.MODEL ?: "watch"

    companion object {
        /** Set by a tapped notification: which chat, and which card on it. */
        const val EXTRA_SESSION_ID = "dev.claudewear.wear.open.sessionId"
        const val EXTRA_REQUEST_ID = "dev.claudewear.wear.open.requestId"
    }
}
