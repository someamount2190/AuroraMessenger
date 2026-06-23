package com.aura.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Per-node signal queues (encrypted WebRTC/pairing payloads). Each queue is bounded to
 * [maxQueueLen] (drop oldest) and tracked by last-activity so idle queues expire on TTL.
 * The server never inspects payloads — it only relays them.
 */
internal class SignalQueues(private val maxQueueLen: Int) {
    private val signals = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val activityAt = ConcurrentHashMap<String, Long>()

    /** Append [payload] to [nodeIdHex]'s queue, bounding it (drop oldest past the cap). */
    fun post(nodeIdHex: String, payload: String, now: Long) {
        val q = signals.getOrPut(nodeIdHex) { ConcurrentLinkedQueue() }
        q.add(payload)
        while (q.size > maxQueueLen) q.poll()
        activityAt[nodeIdHex] = now
    }

    /** Drain and return all queued payloads for [nodeIdHex] in FIFO order. */
    fun drain(nodeIdHex: String, now: Long): List<String> {
        val q = signals[nodeIdHex]
        val out = ArrayList<String>()
        if (q != null) { while (true) { out.add(q.poll() ?: break) } }
        activityAt[nodeIdHex] = now
        return out
    }

    fun purge(cutoff: Long) {
        val stale = signals.keys.filter { (activityAt[it] ?: 0L) < cutoff }
        stale.forEach { signals.remove(it); activityAt.remove(it) }
    }
}
