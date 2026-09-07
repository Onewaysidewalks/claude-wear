package dev.claudewear.spike

import android.service.voice.VoiceInteractionService

/**
 * The service ROLE_ASSISTANT binds to. It has no behaviour; it records that the system bound it
 * (which only happens once we hold the role) and whether it is the active assistant.
 */
class SpikeVoiceInteractionService : VoiceInteractionService() {
    override fun onCreate() {
        super.onCreate()
        InvocationLog.attach(this)
        InvocationLog.record("VIS", "onCreate")
    }

    override fun onReady() {
        super.onReady()
        InvocationLog.record("VIS", "onReady active=${isActiveService(this, componentName())}")
    }

    override fun onShutdown() {
        InvocationLog.record("VIS", "onShutdown")
        super.onShutdown()
    }

    private fun componentName() = android.content.ComponentName(this, SpikeVoiceInteractionService::class.java)
}
