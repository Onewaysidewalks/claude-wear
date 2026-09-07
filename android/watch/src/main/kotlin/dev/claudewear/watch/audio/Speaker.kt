package dev.claudewear.watch.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** On-device text-to-speech, until server speech.chunk events are switched on. */
class Speaker(context: Context) {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

    /** Speaks and suspends until done (or immediately if TTS is unavailable). */
    suspend fun say(text: String, locale: Locale = Locale.getDefault()) {
        if (!ready || text.isBlank()) return
        tts.language = locale
        suspendCancellableCoroutine { cont ->
            val id = "hermes-${System.nanoTime()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { tts.stop() }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
    }

    fun stop() = tts.stop()

    fun shutdown() = tts.shutdown()
}
