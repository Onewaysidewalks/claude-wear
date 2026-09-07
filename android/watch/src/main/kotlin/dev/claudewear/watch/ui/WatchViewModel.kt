package dev.claudewear.watch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.claudewear.shared.ApprovalAnswer
import dev.claudewear.shared.AudioChannel
import dev.claudewear.shared.TextTurn
import dev.claudewear.watch.HermesWatchApp
import dev.claudewear.watch.audio.Recorder
import dev.claudewear.watch.audio.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class WatchViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<HermesWatchApp>()
    private val store get() = app.store
    private val link get() = app.link
    private val speaker = Speaker(application)
    private var recorder: Recorder? = null
    private var job: Job? = null
    private val locale: String get() = Locale.getDefault().toLanguageTag()

    val state: StateFlow<WatchState> = store.state
    val session = "watch"

    init {
        viewModelScope.launch { store.speak.collect { text -> speaker.say(text); store.spoken() } }
        viewModelScope.launch { runCatching { link.ping() } }
    }

    /** Milestone 4: the microphone, streamed to the phone as it is recorded. */
    fun listen() {
        if (state.value.phase == Phase.Listening) { stopListening(); return }
        speaker.stop()
        val requestId = newRequest(Phase.Listening)
        val rec = Recorder().also { recorder = it }
        job = viewModelScope.launch {
            runCatching {
                link.streamAudio(
                    AudioChannel(requestId = requestId, session = session, locale = locale, sampleRate = rec.sampleRate),
                    rec.pcm().onCompletion { store.listeningDone() },
                )
            }.onFailure { store.fail(it.message ?: "could not reach the phone") }
        }
    }

    fun stopListening() {
        recorder?.stop()
    }

    /** Milestone 1: text from the keyboard or the platform recognizer, no audio of ours involved. */
    fun sendText(text: String) {
        if (text.isBlank()) return
        speaker.stop()
        val requestId = newRequest(Phase.Thinking)
        job = viewModelScope.launch {
            runCatching { link.sendText(TextTurn(requestId = requestId, text = text.trim(), session = session, locale = locale)) }
                .onFailure { store.fail(it.message ?: "could not reach the phone") }
        }
    }

    fun decide(decision: String) {
        val s = state.value
        val a = s.approval ?: return
        val requestId = s.requestId ?: return
        viewModelScope.launch {
            runCatching { link.sendApproval(ApprovalAnswer(requestId, a.turnId, a.approvalId, decision)) }
                .onFailure { store.fail(it.message ?: "could not reach the phone") }
        }
    }

    fun cancel() {
        recorder?.stop()
        job?.cancel()
        speaker.stop()
        val requestId = state.value.requestId
        store.reset()
        if (requestId != null) viewModelScope.launch { runCatching { link.sendCancel(requestId) } }
    }

    fun dismissError() = store.reset()

    private fun newRequest(phase: Phase): String {
        job?.cancel()
        recorder?.stop()
        val id = UUID.randomUUID().toString()
        store.begin(id, phase)
        return id
    }

    override fun onCleared() {
        speaker.shutdown()
        super.onCleared()
    }
}
