package com.remodroid.shared.util

/** Token bucket used by hosts to bound the rate of injected input events. */
class TokenBucket(
    private val capacity: Double,
    private val refillPerSecond: Double,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var tokens = capacity
    private var last = nowMs()

    @Synchronized
    fun tryTake(): Boolean {
        val t = nowMs()
        tokens = minOf(capacity, tokens + (t - last) / 1000.0 * refillPerSecond)
        last = t
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
