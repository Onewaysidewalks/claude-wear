package dev.claudewear.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.claudewear.wear.data.DEFAULT_BRIDGE_URL
import dev.claudewear.wear.data.Pairing
import dev.claudewear.wear.data.TokenStore
import dev.claudewear.wear.service.ActiveConnection
import dev.claudewear.wear.service.ConnectionService
import dev.claudewear.wear.ui.ClaudeWearApp
import dev.claudewear.wear.ui.PairingState
import dev.claudewear.wear.ui.WatchActions
import dev.claudewear.wear.ui.WatchState
import kotlinx.coroutines.launch

/**
 * The screens, and nothing else.
 *
 * The connection lives in [ConnectionService] — an Activity that owned the socket would
 * drop it the moment you lowered your wrist, which is exactly when a blocked agent needs
 * to reach you. Here that leaves pairing, which is the one thing that has to happen with
 * someone looking at the screen.
 */
class MainActivity : ComponentActivity() {

    private lateinit var tokens: TokenStore

    private var paired by mutableStateOf(false)
    private var pairing by mutableStateOf(PairingState())

    /** Declined is survivable: the socket still runs, you just stop seeing the chip. */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = TokenStore(this)
        paired = tokens.isPaired
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
                actions = WatchActions(
                    pair = ::pair,
                    unpair = ::unpair,
                    newSession = { cwd -> client?.newSession(cwd) },
                    prompt = { sessionId, text -> client?.prompt(sessionId, text) },
                    interrupt = { sessionId -> client?.interrupt(sessionId) },
                    setMode = { sessionId, mode -> client?.setMode(sessionId, mode) },
                ),
            )
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
}
