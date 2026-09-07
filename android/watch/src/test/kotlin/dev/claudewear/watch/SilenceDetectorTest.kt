package dev.claudewear.watch

import dev.claudewear.watch.audio.SilenceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SilenceDetectorTest {
    private fun tone(amplitude: Int, ms: Int, rate: Int = 16000): ByteArray {
        val n = rate * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (amplitude * sin(2 * Math.PI * 440 * i / rate)).toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun `rms of silence is zero and of a tone is about amplitude over root two`() {
        assertEquals(0.0, SilenceDetector.rms(ByteArray(3200), 3200), 0.0)
        val t = tone(10000, 100)
        assertEquals(10000 / Math.sqrt(2.0), SilenceDetector.rms(t, t.size), 150.0)
    }

    @Test
    fun `does not end before speech and ends after trailing silence`() {
        val d = SilenceDetector(16000, 1000, 500.0)
        val quiet = ByteArray(3200)
        repeat(30) { assertFalse(d.feed(quiet, quiet.size)) } // 3 s of silence, nothing said yet
        val loud = tone(8000, 100)
        repeat(5) { assertFalse(d.feed(loud, loud.size)) }
        repeat(9) { assertFalse(d.feed(quiet, quiet.size)) } // 900 ms
        assertTrue(d.feed(quiet, quiet.size)) // 1000 ms
    }
}
