package com.aura.network

import kotlin.test.Test
import kotlin.test.assertEquals

class BackoffTest {
    private val base = 5_000L
    private val max = 60_000L

    @Test fun firstFailure_isBase() =
        assertEquals(5_000L, Backoff.delayMs(1, base, max))

    @Test fun nonPositiveFailures_returnBase() {
        assertEquals(5_000L, Backoff.delayMs(0, base, max))
        assertEquals(5_000L, Backoff.delayMs(-7, base, max))
    }

    @Test fun doublesEachConsecutiveFailure() {
        assertEquals(5_000L, Backoff.delayMs(1, base, max))
        assertEquals(10_000L, Backoff.delayMs(2, base, max))
        assertEquals(20_000L, Backoff.delayMs(3, base, max))
        assertEquals(40_000L, Backoff.delayMs(4, base, max))
    }

    @Test fun clampsToMax_andNeverOverflows() {
        assertEquals(60_000L, Backoff.delayMs(5, base, max))    // 80s → capped
        assertEquals(60_000L, Backoff.delayMs(31, base, max))   // huge shift → still capped, no overflow
        assertEquals(60_000L, Backoff.delayMs(1_000, base, max))
    }
}
