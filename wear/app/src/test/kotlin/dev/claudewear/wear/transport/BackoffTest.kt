package dev.claudewear.wear.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    private fun backoff(jitter: Double) = Backoff(initialMs = 1_000, maxMs = 8_000, jitter = { jitter })

    @Test
    fun doublesUpToTheCeiling() {
        val backoff = backoff(jitter = 1.0)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..5).map { backoff.nextMs() })
    }

    @Test
    fun neverWaitsLessThanHalf() {
        // The random half is what stops a watch and a bridge that lost the same Wi-Fi from
        // retrying in lockstep; the fixed half is what stops a tight loop.
        val backoff = backoff(jitter = 0.0)
        assertEquals(listOf(500L, 1_000L, 2_000L), (1..3).map { backoff.nextMs() })
    }

    @Test
    fun startsCheapAgainOnceAConnectionProvesItself() {
        val backoff = backoff(jitter = 1.0)
        repeat(4) { backoff.nextMs() }
        backoff.reset()
        assertEquals(1_000L, backoff.nextMs())
    }
}
