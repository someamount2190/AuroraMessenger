package com.aura.server

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The store-and-forward signal queue is bounded. It must REJECT once full rather than drop
 * the oldest — otherwise anyone who knows a victim's node id can flush the victim's pending
 * real signals (pairing/offers) by posting a queue's worth of junk.
 */
class SignalQueuesTest {

    @Test fun rejectsWhenFull_preservesEarliestRealSignals() {
        val q = SignalQueues(maxQueueLen = 3)
        q.post("n", "real-1", now = 1)
        q.post("n", "real-2", now = 2)
        q.post("n", "real-3", now = 3)
        // Queue is full: a flooder's extra posts are rejected, NOT swapped in over the reals.
        q.post("n", "junk-4", now = 4)
        q.post("n", "junk-5", now = 5)
        assertEquals(listOf("real-1", "real-2", "real-3"), q.drain("n", now = 6))
    }

    @Test fun draining_freesSpaceForNewSignals() {
        val q = SignalQueues(maxQueueLen = 2)
        q.post("n", "a", now = 1)
        q.post("n", "b", now = 2)
        q.post("n", "c", now = 3)                       // rejected while full
        assertEquals(listOf("a", "b"), q.drain("n", now = 4))
        q.post("n", "d", now = 5)                       // space again after the drain
        assertEquals(listOf("d"), q.drain("n", now = 6))
    }

    @Test fun queuesAreIndependentPerNode() {
        val q = SignalQueues(maxQueueLen = 1)
        q.post("a", "for-a", now = 1)
        q.post("b", "for-b", now = 1)
        q.post("a", "overflow-a", now = 2)              // a is full; must not affect b
        assertEquals(listOf("for-a"), q.drain("a", now = 3))
        assertEquals(listOf("for-b"), q.drain("b", now = 3))
    }
}
