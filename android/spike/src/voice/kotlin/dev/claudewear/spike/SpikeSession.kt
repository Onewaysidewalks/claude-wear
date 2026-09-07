package dev.claudewear.spike

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/**
 * Shown by the system when the assistant is invoked through the VoiceInteractionService path
 * (on a phone: long-press home, or the nav-bar gesture). Decodes the show flags so the log says
 * where the invocation came from, then opens the main screen so it can be read.
 */
class SpikeSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        InvocationLog.record("VIS_SESSION", "onShow flags=0x${Integer.toHexString(showFlags)} [${decode(showFlags)}] args=${args?.keySet()?.joinToString(",") ?: "-"}")
        startAssistantActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SOURCE, "vis-session")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        hide()
    }

    override fun onHide() {
        InvocationLog.record("VIS_SESSION", "onHide")
        super.onHide()
    }

    private fun decode(flags: Int): String {
        val names = mutableListOf<String>()
        if (flags and SHOW_WITH_ASSIST != 0) names += "WITH_ASSIST"
        if (flags and SHOW_WITH_SCREENSHOT != 0) names += "WITH_SCREENSHOT"
        if (flags and SHOW_SOURCE_ASSIST_GESTURE != 0) names += "SOURCE_ASSIST_GESTURE"
        if (flags and SHOW_SOURCE_PUSH_TO_TALK != 0) names += "SOURCE_PUSH_TO_TALK"
        if (flags and SHOW_SOURCE_NOTIFICATION != 0) names += "SOURCE_NOTIFICATION"
        if (flags and SHOW_SOURCE_ACTIVITY != 0) names += "SOURCE_ACTIVITY"
        if (flags and SHOW_SOURCE_AUTOMOTIVE_SYSTEM_UI != 0) names += "SOURCE_AUTOMOTIVE"
        if (flags and SHOW_SOURCE_APPLICATION != 0) names += "SOURCE_APPLICATION"
        return names.joinToString("|").ifEmpty { "none" }
    }
}
