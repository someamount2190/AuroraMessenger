package com.aura.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-IP sliding-window rate limiter: at most [maxPerMinute] events per requester IP
 * per 60 seconds. Unifies the previously-duplicated `/find` and `/signal` POST limits.
 */
internal class IpRateLimiter(private val maxPerMinute: Int) {
    private val timestampsByIp = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(requesterIp: String, now: Long = System.currentTimeMillis()): Boolean {
        // Bound map growth: when it gets large, evict IP buckets whose timestamps have all expired
        // (otherwise one key accumulates per distinct/rotating source IP, forever → slow memory DoS).
        if (timestampsByIp.size > MAX_TRACKED_IPS) {
            timestampsByIp.entries.removeIf { e ->
                synchronized(e.value) { e.value.removeIf { now - it > WINDOW_MS }; e.value.isEmpty() }
            }
        }
        val timestamps = timestampsByIp.computeIfAbsent(requesterIp) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeIf { now - it > WINDOW_MS }
            if (timestamps.size >= maxPerMinute) return false
            timestamps.add(now)
            return true
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        /** Above this many tracked IPs, sweep expired buckets to bound memory. */
        const val MAX_TRACKED_IPS = 10_000
    }
}
