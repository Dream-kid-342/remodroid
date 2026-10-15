package com.remodroid.shared.util

import kotlin.math.min
import kotlin.random.Random

/** Exponential backoff with "equal jitter" (a random delay between half and all of the cap), used for reconnects. */
class Backoff(
    private val baseMs: Long = 500,
    private val maxMs: Long = 15_000,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun nextDelayMs(): Long {
        val cap = min(maxMs, baseMs shl min(attempt, 20))
        attempt++
        return random.nextLong(cap / 2, cap + 1)
    }

    fun reset() {
        attempt = 0
    }
}
