package com.aura.network

/**
 * Pure exponential-backoff schedule for the sync loop's network retries, extracted from
 * [SyncEngine] so the timing math is unit-testable without the loop or a network.
 */
object Backoff {
    /**
     * Delay before the next retry after [consecutiveFailures] consecutive failures
     * (1 = the first failure → [baseMs]). Doubles each subsequent failure, clamped to
     * [maxMs]. Non-positive failure counts return [baseMs].
     */
    fun delayMs(consecutiveFailures: Int, baseMs: Long, maxMs: Long): Long {
        if (consecutiveFailures <= 0) return baseMs
        // Clamp the shift before shifting so a long failure streak can't overflow Long.
        val shift = (consecutiveFailures - 1).coerceAtMost(MAX_SHIFT)
        return (baseMs shl shift).coerceIn(baseMs, maxMs)
    }

    /** Caps the bit-shift; well past the point delays are clamped to maxMs anyway. */
    private const val MAX_SHIFT = 16
}
