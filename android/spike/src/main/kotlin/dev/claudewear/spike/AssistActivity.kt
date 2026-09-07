package dev.claudewear.spike

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The ACTION_ASSIST / VOICE_ASSIST target. It records how it was reached and hands over to the
 * main screen so the log is visible. Nothing else: the question is whether this runs at all.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent, "onCreate")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, "onNewIntent")
    }

    private fun handle(intent: Intent?, via: String) {
        InvocationLog.record("ASSIST_ACTIVITY", "$via ${InvocationLog.describe(intent)}")
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SOURCE, "assist-activity/${intent?.action}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
