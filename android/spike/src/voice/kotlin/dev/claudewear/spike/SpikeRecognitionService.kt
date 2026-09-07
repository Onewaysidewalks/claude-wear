package dev.claudewear.spike

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * VoiceInteractionServiceInfo refuses metadata with no recognitionService, so one must exist.
 * It recognises nothing; it reports an error the moment anything asks it to listen.
 */
class SpikeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        InvocationLog.attach(this)
        InvocationLog.record("RECOGNIZER", "onStartListening ${InvocationLog.describe(recognizerIntent)}")
        listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    }

    override fun onCancel(listener: Callback) = Unit
    override fun onStopListening(listener: Callback) = Unit
}
