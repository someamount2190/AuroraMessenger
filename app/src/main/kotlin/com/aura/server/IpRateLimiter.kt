package com.aura.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-IP sliding-window rate limiter: at most [maxPerMinute] events per requester IP
 * per 60 seconds. Unifies the previously-duplicated `/find` and `/signal` POST limits.
 */
internal class IpRateLimiter(private val maxPerMinute: Int) {
    private val timestampsByIp = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(requesterIp: String, now: Long = System.currentTimeMillis()): Boolean {
        val timestamps = timestampsByIp.getOrPut(requesterIp) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeIf { now - it > WINDOW_MS }
            if (timestamps.size >= maxPerMinute) return false
            timestamps.add(now)
            return true
        }
    }

    private companion object { const val WINDOW_MS = 60_000L }
}
