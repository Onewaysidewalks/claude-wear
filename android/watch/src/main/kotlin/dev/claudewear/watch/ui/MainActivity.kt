package dev.claudewear.watch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: WatchViewModel by viewModels()
    private var pendingListen = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingListen) vm.listen()
        pendingListen = false
    }

    /** The platform's own recognizer: a text path that needs no audio streaming, and a fallback. */
    private val recognizer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.sendText(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            WatchScreen(
                state = state,
                onListen = ::listen,
                onType = ::recognize,
                onDecide = vm::decide,
                onCancel = vm::cancel,
                onDismissError = vm::dismissError,
            )
        }
        if (isAssistLaunch(intent)) listen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The assistant role brings us here on every button hold: start listening at once.
        if (isAssistLaunch(intent)) listen()
    }

    private fun isAssistLaunch(intent: Intent?) =
        intent?.action == Intent.ACTION_ASSIST || intent?.action == "android.intent.action.VOICE_ASSIST"

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.listen()
        } else {
            pendingListen = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun recognize() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Hermes")
        runCatching { recognizer.launch(intent) }
    }
}
