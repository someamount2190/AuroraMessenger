package com.aura.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Per-node published prekey bundles for forward-secret PQXDH pairing. Stores only the
 * PUBLIC signed-prekey JSON + a bounded pool of one-time-prekey JSONs (never private
 * material). [fetch] pops one one-time prekey per request; the pool drains over time
 * and the handshake then runs SPK-only (still forward-secret). Expires on TTL.
 */
internal class PrekeyDirectory(private val opkMax: Int) {
    private val prekeys = ConcurrentHashMap<String, PrekeyStore>()

    /** Replace [nodeIdHex]'s bundle with [spkJson] + up to [opkMax] one-time prekeys. */
    fun publish(nodeIdHex: String, spkJson: String, opks: List<String>, now: Long) {
        val q = ConcurrentLinkedQueue<String>()
        for (i in 0 until minOf(opks.size, opkMax)) q.add(opks[i])
        prekeys[nodeIdHex] = PrekeyStore(spkJson, q, now)
    }

    /** The signed-prekey JSON plus one popped one-time-prekey JSON (null when exhausted),
     *  or null if no bundle is published for [nodeIdHex]. */
    fun fetch(nodeIdHex: String): Bundle? {
        val store = prekeys[nodeIdHex] ?: return null
        return Bundle(store.spkJson, store.opks.poll())
    }

    fun purge(cutoff: Long) {
        prekeys.entries.removeIf { it.value.storedAtMs < cutoff }
    }

    /** A fetched bundle: the signed-prekey JSON and an optional popped one-time prekey. */
    class Bundle(val spkJson: String, val opkJson: String?)

    private class PrekeyStore(
        val spkJson: String,
        val opks: ConcurrentLinkedQueue<String>,
        @Volatile var storedAtMs: Long
    )
}
