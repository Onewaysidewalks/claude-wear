package dev.claudewear.wear.transport

import kotlin.math.pow
import kotlin.random.Random

/**
 * How long to wait before reaching for the socket again.
 *
 * Pure and injectable because reconnect policy is the part of connectivity that is worth
 * testing and the hardest to observe on a wrist: a watch that reconnects too eagerly is a
 * battery complaint, and one that gives up is a missed permission prompt.
 */
class Backoff(
    private val initialMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val multiplier: Double = 2.0,
    /** Fraction in [0, 1]. Injectable so a test can pin the delay it expects. */
    private val jitter: () -> Double = { Random.nextDouble() },
) {

    private var attempt = 0

    /** Called once a connection has proved itself, so the next outage starts from cheap again. */
    fun reset() {
        attempt = 0
    }

    /**
     * Half fixed, half random. One watch is not a thundering herd, but a watch and a
     * bridge that lost the same Wi-Fi at the same moment will otherwise retry in lockstep
     * into the same dead window, forever.
     */
    fun nextMs(): Long {
        val ceiling = (initialMs * multiplier.pow(attempt)).coerceIn(initialMs.toDouble(), maxMs.toDouble())
        attempt += 1
        val half = ceiling / 2
        return (half + half * jitter().coerceIn(0.0, 1.0)).toLong()
    }
}
