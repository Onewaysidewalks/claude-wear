package dev.claudewear.spike

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class SpikeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        InvocationLog.attach(this)
        InvocationLog.record("VIS", "onNewSession args=${args?.keySet()?.joinToString(",") ?: "-"}")
        return SpikeSession(this)
    }
}
