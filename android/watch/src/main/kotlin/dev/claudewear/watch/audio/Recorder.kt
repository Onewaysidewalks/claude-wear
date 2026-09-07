package dev.claudewear.watch.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.sqrt

/**
 * The microphone as a flow of PCM chunks: 16 kHz, mono, 16-bit, 100 ms per chunk, from the
 * VOICE_RECOGNITION source (which asks the platform for a speech-tuned, unprocessed path).
 * Ends on [stop], on [maxMillis], or after [trailingSilenceMillis] of quiet once speech was heard.
 */
class Recorder(
    val sampleRate: Int = 16_000,
    private val maxMillis: Long = 15_000,
    private val trailingSilenceMillis: Long = 1_200,
    private val silenceRms: Double = 500.0,
) {
    @Volatile private var stopped = false

    fun stop() {
        stopped = true
    }

    @SuppressLint("MissingPermission") // the screen asks for RECORD_AUDIO before it gets here
    fun pcm(): Flow<ByteArray> = flow {
        stopped = false
        val chunkBytes = sampleRate / 10 * 2
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkBytes * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
        val vad = SilenceDetector(sampleRate, trailingSilenceMillis, silenceRms)
        try {
            record.startRecording()
            val buf = ByteArray(chunkBytes)
            var elapsed = 0L
            while (!stopped && elapsed < maxMillis) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) break
                elapsed += n * 1000L / (sampleRate * 2)
                emit(buf.copyOf(n))
                if (vad.feed(buf, n)) break
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}

/** Ends the utterance after [trailingSilenceMillis] of quiet, but only once something was said. */
class SilenceDetector(private val sampleRate: Int, private val trailingSilenceMillis: Long, private val silenceRms: Double) {
    private var heardSpeech = false
    private var quietMillis = 0L

    /** Returns true when the utterance should end. */
    fun feed(buf: ByteArray, n: Int): Boolean {
        val rms = rms(buf, n)
        val millis = n * 1000L / (sampleRate * 2)
        if (rms >= silenceRms) {
            heardSpeech = true
            quietMillis = 0
        } else if (heardSpeech) {
            quietMillis += millis
        }
        return heardSpeech && quietMillis >= trailingSilenceMillis
    }

    companion object {
        fun rms(buf: ByteArray, n: Int): Double {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            val samples = n / 2
            return if (samples == 0) 0.0 else sqrt(sum / samples)
        }
    }
}
