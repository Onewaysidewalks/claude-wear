package dev.claudewear.wear

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.claudewear.wear.data.DEFAULT_BRIDGE_URL
import dev.claudewear.wear.data.Pairing
import dev.claudewear.wear.data.TokenStore
import dev.claudewear.wear.notify.SocketNotifications
import dev.claudewear.wear.transport.WebSocketTransport
import dev.claudewear.wear.ui.ClaudeWearApp
import dev.claudewear.wear.ui.SessionsViewModel
import dev.claudewear.wear.ui.WatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var tokens: TokenStore

    /** Snapshot state, so swapping the ViewModel in after pairing recomposes the screen. */
    private var viewModel by mutableStateOf<SessionsViewModel?>(null)
    private var paired by mutableStateOf(false)
    private var pairingError by mutableStateOf<String?>(null)

    private val disconnected = MutableStateFlow(WatchState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokens = TokenStore(this)
        paired = tokens.isPaired

        setContent {
            val state by (viewModel?.state ?: disconnected).collectAsStateWithLifecycle()
            ClaudeWearApp(
                paired = paired,
                state = state,
                defaultBridgeUrl = tokens.baseUrl ?: DEFAULT_BRIDGE_URL,
                pairingError = pairingError,
                onPair = ::pair,
                onNewSession = { cwd -> viewModel?.newSession(cwd) },
            )
        }

        if (paired) connect()
    }

    private fun pair(baseUrl: String, code: String) {
        lifecycleScope.launch {
            runCatching { Pairing.pair(baseUrl, code, deviceName()) }
                .onSuccess { result ->
                    tokens.save(baseUrl, result)
                    pairingError = null
                    paired = true
                    connect()
                }
                .onFailure { pairingError = it.message }
        }
    }

    private fun connect() {
        val url = tokens.baseUrl ?: return
        val token = tokens.token ?: return
        viewModel?.stop()
        viewModel = SessionsViewModel(
            transport = WebSocketTransport(url, token),
            notifications = SocketNotifications(this),
            scope = lifecycleScope,
            deviceId = tokens.deviceId ?: "unknown",
            deviceName = deviceName(),
        ).also { it.start() }
    }

    private fun deviceName(): String = Build.MODEL ?: "watch"

    override fun onDestroy() {
        viewModel?.stop()
        super.onDestroy()
    }
}
