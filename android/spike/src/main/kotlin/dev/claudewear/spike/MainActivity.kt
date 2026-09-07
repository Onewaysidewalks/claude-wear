package dev.claudewear.spike

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SOURCE = "source"
    }

    private var status by mutableStateOf<AssistantStatus?>(null)

    // ROLE_ASSISTANT is not requestable through this intent on stock Android (the role is
    // marked requestable=false); the request returns RESULT_CANCELED immediately. We ask
    // anyway and log the answer, because "Samsung is different" is exactly what we are here
    // to find out.
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val verdict = if (result.resultCode == Activity.RESULT_OK) "granted" else "code=${result.resultCode}"
        InvocationLog.record("ROLE_REQUEST", "result $verdict")
        status = AssistantStatus.read(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InvocationLog.attach(this)
        InvocationLog.record("MAIN", "onCreate source=${intent.getStringExtra(EXTRA_SOURCE) ?: "launcher"} ${InvocationLog.describe(intent)}")
        setContent {
            SpikeScreen(
                status = status,
                onRequestRole = ::requestRole,
                onOpenDefaultApps = { open("android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "default-apps") },
                onOpenVoiceInput = { open(Settings.ACTION_VOICE_INPUT_SETTINGS, "voice-input") },
                onClear = InvocationLog::clear,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        InvocationLog.record("MAIN", "onNewIntent source=${intent.getStringExtra(EXTRA_SOURCE) ?: "-"} ${InvocationLog.describe(intent)}")
    }

    override fun onResume() {
        super.onResume()
        status = AssistantStatus.read(this)
    }

    // Wear docs: an app in the foreground may see KEYCODE_STEM_1..3 but never the primary
    // button. Samsung says Home/Back on Watch4+ are system keys. Log whatever arrives.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        InvocationLog.record("KEY", "down code=$keyCode (${KeyEvent.keyCodeToString(keyCode)}) repeat=${event.repeatCount}")
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        InvocationLog.record("KEY", "longpress code=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
        return super.onKeyLongPress(keyCode, event)
    }

    private fun requestRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            InvocationLog.record("ROLE_REQUEST", "role unavailable on this build")
            return
        }
        InvocationLog.record("ROLE_REQUEST", "launching createRequestRoleIntent(ASSISTANT)")
        runCatching { roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) }
            .onFailure { InvocationLog.record("ROLE_REQUEST", "launch failed: $it") }
    }

    private fun open(action: String, label: String) {
        runCatching { startActivity(Intent(action)) }
            .onSuccess { InvocationLog.record("SETTINGS", "opened $label") }
            .onFailure { InvocationLog.record("SETTINGS", "$label not available: ${it.javaClass.simpleName}") }
    }
}
